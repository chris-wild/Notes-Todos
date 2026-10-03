// One Durable Object per ops account (an anonymous UUID minted by the app and kept in the
// iCloud-synchronizable Keychain). SQLite-backed storage is the only kind the Workers Free
// plan allows. Every balance mutation runs inside this object, so two devices sharing one
// token — or two parallel extractions — can never double-spend the way an eventually
// consistent store would let them.
//
// Storage layout (key-value API over the SQLite backend, RiderNav convention):
//   "meta"      -> { createdAt, freeGranted, starter? } starter is "pending" until the device is
//                  checked (src/starter.js), then "granted" or "denied"; absent on accounts made
//                  by clients from before device checks, whose free ops were granted at creation
//   "balance"   -> integer ops remaining              may go NEGATIVE after a refund
//   "txn:<id>"  -> { ops, productId, environment | platform+orderId+purchaseType, refunded, at }
//                  the money trail, kept forever; <id> is Apple's transaction id or "google:…"
//   "op:<id>"   -> { pages, at }                      a spend reservation; deleted on settle
//   "titles"    -> { day, count }                     free-title rate limit window

import { DurableObject } from "cloudflare:workers";

const DAY_MS = 24 * 60 * 60 * 1000;
const LIMITS = { newAccount: { max: 25, windowMs: DAY_MS } };

export class OpsAccount extends DurableObject {
  async meta() {
    return (await this.ctx.storage.get("meta")) || null;
  }

  /**
   * Make sure the account exists. Creation is rationed per client address so scripted fresh
   * UUIDs cannot farm free extractions. [deviceChecked] says whether the free starter ops wait
   * for a device check (src/starter.js) or, for clients from before device checks, are granted
   * at once. Returns { balance, starter } or { limited: true }.
   */
  async ensure(address, deviceChecked) {
    const meta = await this.meta();
    if (meta) return { balance: await this.ctx.storage.get("balance"), starter: meta.starter ?? "granted" };
    const allowed = await this.env.LIMITER.get(this.env.LIMITER.idFromName(address || "unknown"))
      .check("newAccount", true);
    if (!allowed) return { limited: true };
    return this.create(deviceChecked);
  }

  async create(deviceChecked) {
    const free = Number(this.env.FREE_OPS || 0);
    const meta = deviceChecked
      ? { createdAt: Date.now(), freeGranted: 0, starter: "pending" }
      : { createdAt: Date.now(), freeGranted: free };
    await this.ctx.storage.put("meta", meta);
    await this.ctx.storage.put("balance", meta.freeGranted);
    return { balance: meta.freeGranted, starter: meta.starter ?? "granted" };
  }

  /** "pending", "granted" or "denied"; accounts from before device checks count as granted. */
  async starter() {
    const meta = await this.meta();
    return { starter: meta ? meta.starter ?? "granted" : "pending", balance: (await this.ctx.storage.get("balance")) ?? 0 };
  }

  /**
   * Settle a pending starter grant once: [granted] adds the free ops, otherwise the account is
   * marked denied. A second call changes nothing, so two racing device checks grant at most once.
   */
  async resolveStarter(granted) {
    const meta = await this.meta();
    if (!meta || (meta.starter ?? "granted") !== "pending") return this.starter();
    const free = Number(this.env.FREE_OPS || 0);
    let balance = await this.ctx.storage.get("balance");
    if (granted) {
      balance += free;
      await this.ctx.storage.put("balance", balance);
    }
    await this.ctx.storage.put("meta", { ...meta, starter: granted ? "granted" : "denied", freeGranted: granted ? free : 0 });
    return { starter: granted ? "granted" : "denied", balance };
  }

  /**
   * Erase everything this account holds, for a deletion request. Returns the Google transaction
   * ids it had, so the caller can remove them from the order index too.
   */
  async erase() {
    if ((await this.meta()) === null && (await this.ctx.storage.list({ limit: 1 })).size === 0) {
      return { existed: false, googleTxnIds: [] };
    }
    const txns = await this.ctx.storage.list({ prefix: "txn:google:" });
    const googleTxnIds = [...txns.keys()].map((key) => key.slice("txn:".length));
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    return { existed: true, googleTxnIds };
  }

  /** Hold [pages] ops for one extraction call. { ok, balance } or { ok: false, needed, balance }. */
  async reserve(opId, pages) {
    if ((await this.meta()) === null) return { ok: false, needed: pages, balance: 0 };
    const balance = await this.ctx.storage.get("balance");
    if (balance < pages) return { ok: false, needed: pages, balance };
    await this.ctx.storage.put("balance", balance - pages);
    await this.ctx.storage.put(`op:${opId}`, { pages, at: Date.now() });
    return { ok: true, balance: balance - pages };
  }

  /**
   * Close a reservation: commit keeps the spend, refund puts the pages back (the upstream
   * call failed, the user pays nothing). Idempotent — a second settle finds no record.
   */
  async settle(opId, commit) {
    const op = await this.ctx.storage.get(`op:${opId}`);
    if (!op) return;
    await this.ctx.storage.delete(`op:${opId}`);
    if (!commit) {
      await this.ctx.storage.put("balance", (await this.ctx.storage.get("balance")) + op.pages);
    }
  }

  /**
   * Credit a verified purchase. Idempotent by transaction id (Apple's, or "google:<orderId>"),
   * so a replayed JWS or purchase token — or the client's retry after a lost response — can
   * never double-credit.
   * A purchase also creates the account (no address rationing: money changed hands).
   */
  async credit(txnId, ops, detail, deviceChecked = false) {
    const existing = await this.ctx.storage.get(`txn:${txnId}`);
    if (existing) return { balance: await this.ctx.storage.get("balance"), credited: 0, duplicate: true };
    if ((await this.meta()) === null) await this.create(deviceChecked);
    const balance = (await this.ctx.storage.get("balance")) + ops;
    await this.ctx.storage.put("balance", balance);
    await this.ctx.storage.put(`txn:${txnId}`, { ops, ...detail, refunded: false, at: Date.now() });
    await this.ctx.storage.put("purchased", true);
    return { balance, credited: ops, duplicate: false };
  }

  /**
   * Whether this account has ever bought a pack. The flag is written by credit();
   * accounts credited before the flag existed are migrated by the txn: scan.
   */
  async hasPurchased() {
    if (await this.ctx.storage.get("purchased")) return true;
    const txns = await this.ctx.storage.list({ prefix: "txn:", limit: 1 });
    if (txns.size === 0) return false;
    await this.ctx.storage.put("purchased", true);
    return true;
  }

  /**
   * The store refunded a pack: take its ops back, letting the balance go negative — an account
   * that spent a refunded pack stays underwater until the next purchase, which is exactly
   * the deterrent buy-use-refund farming needs. Idempotent per transaction.
   */
  async refundPurchase(txnId) {
    const txn = await this.ctx.storage.get(`txn:${txnId}`);
    if (!txn || txn.refunded) return { debited: 0, balance: (await this.ctx.storage.get("balance")) ?? 0 };
    const balance = (await this.ctx.storage.get("balance")) - txn.ops;
    await this.ctx.storage.put("balance", balance);
    await this.ctx.storage.put(`txn:${txnId}`, { ...txn, refunded: true });
    return { debited: txn.ops, balance };
  }

  /** Title calls are free but not unlimited: [limit] per UTC day per account. */
  async titleAllowed(limit) {
    const day = new Date().toISOString().slice(0, 10);
    const titles = (await this.ctx.storage.get("titles")) || { day, count: 0 };
    if (titles.day !== day) {
      titles.day = day;
      titles.count = 0;
    }
    if (titles.count >= limit) return false;
    titles.count += 1;
    await this.ctx.storage.put("titles", titles);
    return true;
  }
}

// One object per client address, RiderNav's limiter verbatim: it remembers recent account
// creations so a stranger minting fresh UUIDs is stopped after LIMITS.newAccount.max a day.
export class Limiter extends DurableObject {
  async check(kind, count) {
    const rule = LIMITS[kind];
    if (!rule) return false;
    const now = Date.now();
    const recent = ((await this.ctx.storage.get(kind)) || []).filter((t) => now - t < rule.windowMs);
    if (recent.length >= rule.max) return false;
    if (count) {
      recent.push(now);
      await this.ctx.storage.put(kind, recent);
      if ((await this.ctx.storage.getAlarm()) === null) {
        await this.ctx.storage.setAlarm(now + rule.windowMs + 60_000);
      }
    }
    return true;
  }

  /** Forget what has aged out of its window; keep, and re-arm for, whatever has not. */
  async alarm() {
    const now = Date.now();
    let next = 0;
    for (const [kind, rule] of Object.entries(LIMITS)) {
      const recent = ((await this.ctx.storage.get(kind)) || []).filter((t) => now - t < rule.windowMs);
      if (recent.length === 0) {
        await this.ctx.storage.delete(kind);
      } else {
        await this.ctx.storage.put(kind, recent);
        const expires = Math.min(...recent) + rule.windowMs + 60_000;
        next = next === 0 ? expires : Math.min(next, expires);
      }
    }
    if (next > 0) await this.ctx.storage.setAlarm(next);
  }
}
