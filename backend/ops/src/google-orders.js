// The Google Play reverse index: one SQLite-backed object (idFromName("orders")) mapping each
// credited Google purchase to the account it credited. Google's Voided Purchases API reports
// refunds by order id with no account attached, so without this index a refund could not be
// taken back from anyone.
//
// Storage layout (key-value API over the SQLite backend):
//   "order:<txnId>" -> { token, at }   txnId is "google:<orderId>" (see src/google.js), kept forever
//   "checkpoint"    -> ms epoch         start of the last voided-purchases pass that succeeded

import { DurableObject } from "cloudflare:workers";

// storage.get() accepts at most 128 keys per call.
const GET_BATCH = 128;

export class GoogleOrders extends DurableObject {
  /** Remember which account [txnId] credited. The first writer wins; replays change nothing. */
  async record(txnId, token) {
    const key = `order:${txnId}`;
    if (!(await this.ctx.storage.get(key))) await this.ctx.storage.put(key, { token, at: Date.now() });
  }

  /** { [txnId]: token } for the ids this index knows; unknown ids are simply absent. */
  async lookup(txnIds) {
    const found = {};
    for (let i = 0; i < txnIds.length; i += GET_BATCH) {
      const rows = await this.ctx.storage.get(txnIds.slice(i, i + GET_BATCH).map((id) => `order:${id}`));
      for (const [key, row] of rows) found[key.slice("order:".length)] = row.token;
    }
    return found;
  }

  async checkpoint() {
    return (await this.ctx.storage.get("checkpoint")) ?? null;
  }

  async setCheckpoint(ms) {
    await this.ctx.storage.put("checkpoint", ms);
  }
}
