// Metering behavior through the real Worker (vitest-pool-workers): free grant, reserve/
// commit/refund around a mocked Anthropic upstream, the 402 shape the Kotlin client parses,
// page counting, the title cap, and purchase crediting via the staging-only TEST_MODE shape.

import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { env, SELF } from "cloudflare:test";
import { countPages } from "../src/index.js";

const pdfWithPages = (n) =>
  "%PDF-1.4\n" +
  "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n" +
  `2 0 obj << /Type /Pages /Kids [] /Count ${n} >> endobj\n` +
  Array.from({ length: n }, (_, i) => `${3 + i} 0 obj << /Type /Page /Parent 2 0 R >> endobj\n`).join("") +
  "trailer << /Root 1 0 R >>\n";

const b64 = (text) => Buffer.from(text, "latin1").toString("base64");
const anthropicOk = JSON.stringify({ content: [{ type: "text", text: '"ingredients": ["1 egg"]}' }] });

// The worker runs in the same isolate as the tests (vitest-pool-workers), so stubbing the
// global fetch intercepts its Anthropic upstream; SELF.fetch rides a binding and is untouched.
const realFetch = globalThis.fetch;
const upstreamQueue = [];
const upstreamBodies = [];
function mockAnthropic(status, body, times = 1) {
  for (let i = 0; i < times; i++) upstreamQueue.push({ status, body });
}

const call = (path, { token = crypto.randomUUID(), body, method = "POST" } = {}) =>
  SELF.fetch(`https://ops.test${path}`, {
    method,
    headers: token ? { authorization: `Bearer ${token}` } : {},
    body,
  });

beforeAll(() => {
  globalThis.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    if (!url.startsWith("https://api.anthropic.com/")) return realFetch(input, init);
    const next = upstreamQueue.shift();
    if (!next) throw new Error(`unexpected upstream call to ${url}`);
    upstreamBodies.push(typeof init?.body === "string" ? init.body : String(init?.body ?? ""));
    return new Response(next.body, { status: next.status, headers: { "content-type": "application/json" } });
  };
});
afterAll(() => {
  globalThis.fetch = realFetch;
});
afterEach(() => expect(upstreamQueue.length, "unconsumed mocked upstream replies").toBe(0));

describe("page counting", () => {
  it("counts /Type /Page objects, never the /Pages tree", () => {
    expect(countPages(Buffer.from(pdfWithPages(1), "latin1"))).toBe(1);
    expect(countPages(Buffer.from(pdfWithPages(3), "latin1"))).toBe(3);
  });
  it("bills at least one page when no page objects are visible (ObjStm undercount)", () => {
    expect(countPages(Buffer.from("%PDF-1.7 nothing here", "latin1"))).toBe(1);
  });
});

describe("balance and free grant", () => {
  it("grants the starter ops on first sight and remembers the account", async () => {
    const token = crypto.randomUUID();
    const first = await call("/v1/balance", { token, method: "GET" });
    expect(first.status).toBe(200);
    expect(await first.json()).toEqual({ balance: 5, purchased: false, starter: "granted" });
    const again = await call("/v1/balance", { token, method: "GET" });
    expect(await again.json()).toEqual({ balance: 5, purchased: false, starter: "granted" });
  });

  it("refuses a missing or malformed bearer", async () => {
    expect((await call("/v1/balance", { token: null, method: "GET" })).status).toBe(401);
    expect((await call("/v1/balance", { token: "not-a-uuid", method: "GET" })).status).toBe(401);
  });
});

describe("extraction metering", () => {
  it("charges the server-counted pages and passes Anthropic's reply through verbatim", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, anthropicOk);
    const reply = await call("/v1/extract?name=Flapjacks", { token, body: b64(pdfWithPages(2)) });
    expect(reply.status).toBe(200);
    expect(await reply.text()).toBe(anthropicOk);
    const balance = await call("/v1/balance", { token, method: "GET" });
    expect(await balance.json()).toEqual({ balance: 3, purchased: false, starter: "granted" }); // 5 - 2 pages
  });

  it("answers 402 with needed/balance when the pack is short, spending nothing", async () => {
    const token = crypto.randomUUID();
    const reply = await call("/v1/extract", { token, body: b64(pdfWithPages(9)) });
    expect(reply.status).toBe(402);
    const body = await reply.json();
    expect(body.needed).toBe(9);
    expect(body.balance).toBe(5);
    expect(body.error.message).toContain("need 9, have 5");
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 5, purchased: false, starter: "granted" });
  });

  it("refunds the reservation when the upstream call fails", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(529, '{"error":{"type":"overloaded_error","message":"Overloaded"}}');
    const reply = await call("/v1/extract", { token, body: b64(pdfWithPages(3)) });
    expect(reply.status).toBe(529);
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 5, purchased: false, starter: "granted" });
  });

  it("masks an upstream auth failure as 502 (never 'your key is invalid') and refunds", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(401, '{"error":{"type":"authentication_error","message":"invalid x-api-key"}}');
    const reply = await call("/v1/extract", { token, body: b64(pdfWithPages(1)) });
    expect(reply.status).toBe(502);
    expect(await reply.text()).not.toContain("x-api-key");
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 5, purchased: false, starter: "granted" });
  });

  it("rejects a body that is not a PDF", async () => {
    const reply = await call("/v1/extract", { body: b64("just some text") });
    expect(reply.status).toBe(422);
  });

  it("charges one op for notes-text extraction", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, anthropicOk);
    const reply = await call("/v1/extract-text?name=Soup", { token, body: "carrots, 2 onions" });
    expect(reply.status).toBe(200);
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 4, purchased: false, starter: "granted" });
  });
});

describe("free title calls", () => {
  it("relays without charging, up to the daily cap", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, '{"content":[{"type":"text","text":"\\"title\\": \\"Flapjacks\\"}"}]}', 2);
    for (let i = 0; i < 2; i++) {
      expect((await call("/v1/title", { token, body: b64(pdfWithPages(1)) })).status).toBe(200);
    }
    // TITLE_DAILY_LIMIT is 2 under test (vitest.config.js): the third call is refused unpaid.
    expect((await call("/v1/title", { token, body: b64(pdfWithPages(1)) })).status).toBe(429);
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 5, purchased: false, starter: "granted" });
  });
});

describe("free title calls", () => {
  it("skips the free cap while purchased credits remain, and reverts when they run out", async () => {
    const token = crypto.randomUUID();
    const buy = await SELF.fetch("https://ops.test/v1/purchase", {
      method: "POST",
      body: JSON.stringify({ test: { productId: "uk.co.promptbuilt.hobpad.ops50", transactionId: "t-title", appAccountToken: token } }),
    });
    expect(buy.status).toBe(200);
    // Purchased + balance > 0: the PAID ceiling (4 under test) applies, not the free cap (2).
    mockAnthropic(200, '{"content":[{"type":"text","text":"\\"title\\": \\"Flapjacks\\"}"}]}', 4);
    for (let i = 0; i < 4; i++) {
      expect((await call("/v1/title", { token, body: b64(pdfWithPages(1)) })).status).toBe(200);
    }
    expect((await call("/v1/title", { token, body: b64(pdfWithPages(1)) })).status).toBe(429);
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 55, purchased: true, starter: "granted" });
  });

  it("keeps the free cap for a purchased account whose credits are spent", async () => {
    const token = crypto.randomUUID();
    await SELF.fetch("https://ops.test/v1/purchase", {
      method: "POST",
      body: JSON.stringify({ test: { productId: "uk.co.promptbuilt.hobpad.ops50", transactionId: "t-drained", appAccountToken: token } }),
    });
    // Drain all 55 credits (5 free + 50) with one 55-page extraction.
    mockAnthropic(200, anthropicOk);
    expect((await call("/v1/extract", { token, body: b64(pdfWithPages(55)) })).status).toBe(200);
    expect(await (await call("/v1/balance", { token, method: "GET" })).json()).toEqual({ balance: 0, purchased: true, starter: "granted" });
    // Balance 0: back to the free cap of 2.
    mockAnthropic(200, '{"content":[{"type":"text","text":"\\"title\\": \\"Flapjacks\\"}"}]}', 2);
    for (let i = 0; i < 2; i++) {
      expect((await call("/v1/title", { token, body: b64(pdfWithPages(1)) })).status).toBe(200);
    }
    expect((await call("/v1/title", { token, body: b64(pdfWithPages(1)) })).status).toBe(429);
  });
});

describe("naming a page with no recipe", () => {
  it("turns a null title into a 422 the clients treat as naming failed", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, '{"content":[{"type":"text","text":" \\"title\\": null}"}]}');
    const reply = await call("/v1/title", { token, body: b64(pdfWithPages(1)) });
    expect(reply.status).toBe(422);
  });

  it("still relays a real title unchanged", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, '{"content":[{"type":"text","text":"\\"title\\": \\"Flapjacks\\"}"}]}');
    const reply = await call("/v1/title", { token, body: b64(pdfWithPages(1)) });
    expect(reply.status).toBe(200);
    expect(await reply.text()).toContain("Flapjacks");
  });
});

describe("unit conversion preference", () => {
  it("injects the approved conversion clause only when units= is given", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, anthropicOk, 2);
    await call("/v1/extract-text?name=Soup&units=metric", { token, body: "1 cup flour" });
    expect(upstreamBodies.at(-1)).toContain("Convert every quantity to metric units (grams, millilitres)");
    await call("/v1/extract-text?name=Soup", { token, body: "1 cup flour" });
    expect(upstreamBodies.at(-1)).not.toContain("Convert every quantity");
  });

  it("ignores unknown unit systems", async () => {
    const token = crypto.randomUUID();
    mockAnthropic(200, anthropicOk);
    await call("/v1/extract-text?name=Soup&units=cubits", { token, body: "1 cup flour" });
    expect(upstreamBodies.at(-1)).not.toContain("Convert every quantity");
  });
});

describe("purchases (staging TEST_MODE shape)", () => {
  const purchase = (test) =>
    SELF.fetch("https://ops.test/v1/purchase", { method: "POST", body: JSON.stringify({ test }) });

  it("credits a pack once, idempotent by transaction id", async () => {
    const token = crypto.randomUUID();
    const test = { productId: "uk.co.promptbuilt.hobpad.ops50", transactionId: "t-1", appAccountToken: token };
    const first = await purchase(test);
    expect(first.status).toBe(200);
    expect(await first.json()).toEqual({ balance: 55, credited: 50, duplicate: false }); // 5 free + 50
    const replay = await purchase(test);
    expect(await replay.json()).toEqual({ balance: 55, credited: 0, duplicate: true });
  });

  it("rejects unknown products and missing account tokens", async () => {
    expect((await purchase({ productId: "nope", transactionId: "t", appAccountToken: crypto.randomUUID() })).status).toBe(422);
    expect((await purchase({ productId: "uk.co.promptbuilt.hobpad.ops50", transactionId: "t" })).status).toBe(422);
  });

  it("takes a refunded pack back, letting the balance go negative, once", async () => {
    const token = crypto.randomUUID();
    await purchase({ productId: "uk.co.promptbuilt.hobpad.ops100", transactionId: "t-r", appAccountToken: token });
    const account = env.OPS.get(env.OPS.idFromName(token.toLowerCase()));
    // Spend most of it, then Apple refunds the pack: 105 - 90 - 100 = -85.
    expect((await account.reserve("op-x", 90)).ok).toBe(true);
    await account.settle("op-x", true);
    expect(await account.refundPurchase("t-r")).toEqual({ debited: 100, balance: -85 });
    expect(await account.refundPurchase("t-r")).toEqual({ debited: 0, balance: -85 });
  });

  it("serialises concurrent spends on one account (no double-spend)", async () => {
    const token = crypto.randomUUID();
    const account = env.OPS.get(env.OPS.idFromName(token.toLowerCase()));
    await account.ensure("test-ip"); // 5 free ops
    const results = await Promise.all(
      Array.from({ length: 5 }, (_, i) => account.reserve(`op-${i}`, 2)),
    );
    expect(results.filter((r) => r.ok).length).toBe(2); // 2+2 fit in 5, the rest refused
  });
});
