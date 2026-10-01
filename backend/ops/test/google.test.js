// Google Play purchases and refunds through the real Worker. A throwaway RSA key stands in for
// the service account; the OAuth token endpoint and the Play Developer API are stubbed through
// the global fetch (same pattern as metering.test.js), and the token stub VERIFIES each RS256
// assertion against the throwaway public key, so a broken signer fails here.
//
// The secret is generated per run, so these tests call the Worker's handlers directly with an
// env that adds it; SELF.fetch (bindings only, no secret) covers the unconfigured case.

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createExecutionContext, createScheduledController, env, SELF, waitOnExecutionContext } from "cloudflare:test";
import worker from "../src/index.js";
import { purchaseVerdict, resetGoogleAuthCache } from "../src/google.js";

const PKG = "uk.co.promptbuilt.hobpad";
const OPS50 = "uk.co.promptbuilt.hobpad.ops50";
const OPS100 = "uk.co.promptbuilt.hobpad.ops100";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";
const ACCESS_TOKEN = "ya29.test-access-token";
const CLIENT_EMAIL = "ops-verifier@hobpad-test.iam.gserviceaccount.com";

let publicKey;
let testEnv;

// purchaseToken -> { status, body } served by the products.get stub.
const purchases = new Map();
// Pages served, in order, by the voidedpurchases stub.
const voidedPages = [];
const voidedRequests = [];
const assertions = [];
let tokenEndpointStatus = 200;
let productsStatusOverride = null;

const realFetch = globalThis.fetch;
const jsonReply = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

async function verifyAssertion(assertion) {
  const [header, claims, signature] = assertion.split(".");
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    Buffer.from(signature, "base64url"),
    new TextEncoder().encode(`${header}.${claims}`),
  );
  return { ok, header: JSON.parse(Buffer.from(header, "base64url")), claims: JSON.parse(Buffer.from(claims, "base64url")) };
}

beforeAll(async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  publicKey = pair.publicKey;
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const pem = `-----BEGIN PRIVATE KEY-----\n${pkcs8.match(/.{1,64}/g).join("\n")}\n-----END PRIVATE KEY-----\n`;
  testEnv = {
    ...env,
    GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify({
      type: "service_account",
      client_email: CLIENT_EMAIL,
      private_key: pem,
      token_uri: TOKEN_URL,
    }),
  };

  globalThis.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    if (url === TOKEN_URL) {
      const form = new URLSearchParams(init.body);
      expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
      const verified = await verifyAssertion(form.get("assertion"));
      assertions.push(verified);
      if (!verified.ok) return jsonReply(400, { error: "invalid_grant" });
      if (tokenEndpointStatus !== 200) return jsonReply(tokenEndpointStatus, { error: "invalid_grant" });
      return jsonReply(200, { access_token: ACCESS_TOKEN, expires_in: 3599, token_type: "Bearer" });
    }
    if (!url.startsWith(API)) return realFetch(input, init);
    if (init?.headers?.authorization !== `Bearer ${ACCESS_TOKEN}`) return jsonReply(401, { error: { code: 401 } });
    const path = new URL(url).pathname.slice(new URL(API).pathname.length).split("/").map(decodeURIComponent);
    if (path[1] === "purchases" && path[2] === "voidedpurchases") {
      voidedRequests.push(new URL(url).searchParams);
      const next = voidedPages.shift();
      if (!next) throw new Error("unexpected voidedpurchases call");
      return jsonReply(next.status, next.body);
    }
    // [pkg, "purchases", "products", productId, "tokens", purchaseToken]
    if (productsStatusOverride) return jsonReply(productsStatusOverride, { error: { code: productsStatusOverride } });
    const entry = purchases.get(path[5]);
    if (!entry || path[0] !== PKG) return jsonReply(404, { error: { code: 404, message: "not found" } });
    return jsonReply(entry.status ?? 200, entry.body);
  };
});
afterAll(() => {
  globalThis.fetch = realFetch;
});
beforeEach(() => {
  tokenEndpointStatus = 200;
  productsStatusOverride = null;
});
afterEach(() => expect(voidedPages.length, "unconsumed voidedpurchases pages").toBe(0));

/** Register a Play purchase with the stub; returns its purchase token. */
function playPurchase(fields) {
  const purchaseToken = `pt-${crypto.randomUUID()}`;
  purchases.set(purchaseToken, {
    body: {
      kind: "androidpublisher#productPurchase",
      purchaseTimeMillis: String(Date.now()),
      purchaseState: 0,
      consumptionState: 0,
      acknowledgementState: 0,
      orderId: `GPA.${crypto.randomUUID()}`,
      ...fields,
    },
  });
  return purchaseToken;
}

const orderIdOf = (purchaseToken) => purchases.get(purchaseToken).body.orderId;

async function submit(token, body, e = testEnv) {
  const request = new Request("https://ops.test/v1/purchase/google", {
    method: "POST",
    headers: token ? { authorization: `Bearer ${token}`, "content-type": "application/json" } : {},
    body: JSON.stringify(body),
  });
  const reply = await worker.fetch(request, e);
  return { status: reply.status, body: await reply.json() };
}

const buy = (token, purchaseToken, productId = OPS50) => submit(token, { packageName: PKG, productId, purchaseToken });

async function balanceOf(token) {
  const reply = await SELF.fetch("https://ops.test/v1/balance", { headers: { authorization: `Bearer ${token}` } });
  return reply.json();
}

async function runCron(e = testEnv) {
  const ctx = createExecutionContext();
  await worker.scheduled(createScheduledController({ scheduledTime: Date.now(), cron: "0 */6 * * *" }), e, ctx);
  await waitOnExecutionContext(ctx);
}

describe("POST /v1/purchase/google", () => {
  it("credits a verified purchase and reports purchased on /v1/balance", async () => {
    const token = crypto.randomUUID();
    const reply = await buy(token, playPurchase({ obfuscatedExternalAccountId: token }));
    expect(reply).toEqual({ status: 200, body: { balance: 55, credited: 50, duplicate: false } }); // 5 free + 50
    // The naming-cap exemption keys off purchased: a Google-funded account must report it.
    expect(await balanceOf(token)).toEqual({ balance: 55, purchased: true });
  });

  it("signs a valid RS256 service-account assertion and reuses the access token", async () => {
    resetGoogleAuthCache();
    const before = assertions.length;
    const token = crypto.randomUUID();
    expect((await buy(token, playPurchase({ obfuscatedExternalAccountId: token }))).status).toBe(200);
    expect((await buy(token, playPurchase({ obfuscatedExternalAccountId: token }))).status).toBe(200);
    expect(assertions.length - before).toBe(1);
    const { ok, header, claims } = assertions.at(-1);
    expect(ok).toBe(true);
    expect(header).toEqual({ alg: "RS256", typ: "JWT" });
    expect(claims).toMatchObject({
      iss: CLIENT_EMAIL,
      scope: "https://www.googleapis.com/auth/androidpublisher",
      aud: TOKEN_URL,
    });
    expect(claims.exp - claims.iat).toBe(3600);
  });

  it("is idempotent by order id: a replay reports duplicate and credits nothing", async () => {
    const token = crypto.randomUUID();
    const purchaseToken = playPurchase({ obfuscatedExternalAccountId: token });
    expect((await buy(token, purchaseToken)).body).toEqual({ balance: 55, credited: 50, duplicate: false });
    expect(await buy(token, purchaseToken)).toEqual({ status: 200, body: { balance: 55, credited: 0, duplicate: true } });
    expect((await balanceOf(token)).balance).toBe(55);
  });

  it("multiplies by quantity and accepts the bearer in any case", async () => {
    const token = crypto.randomUUID();
    const reply = await buy(token.toUpperCase(), playPurchase({ obfuscatedExternalAccountId: token, quantity: 3 }), OPS100);
    expect(reply.body).toEqual({ balance: 305, credited: 300, duplicate: false });
  });

  it("refuses to credit an account other than the purchaser's", async () => {
    const buyer = crypto.randomUUID();
    const thief = crypto.randomUUID();
    const reply = await buy(thief, playPurchase({ obfuscatedExternalAccountId: buyer }));
    expect(reply.status).toBe(403);
    expect(reply.body.error.type).toBe("account_mismatch");
    // A purchase with no account id at all is refused the same way.
    expect((await buy(thief, playPurchase({}))).body.error.type).toBe("account_mismatch");
  });

  it("answers 409 for a pending purchase", async () => {
    const token = crypto.randomUUID();
    const reply = await buy(token, playPurchase({ obfuscatedExternalAccountId: token, purchaseState: 2 }));
    expect(reply).toEqual({ status: 409, body: { error: { type: "pending", message: expect.any(String) } } });
  });

  it("answers 422 for a cancelled purchase", async () => {
    const token = crypto.randomUUID();
    const reply = await buy(token, playPurchase({ obfuscatedExternalAccountId: token, purchaseState: 1 }));
    expect(reply.status).toBe(422);
    expect(reply.body.error.type).toBe("invalid_purchase");
  });

  it("accepts a license-tester purchase while ALLOW_GOOGLE_TEST_PURCHASES is 1", async () => {
    const token = crypto.randomUUID();
    const reply = await buy(token, playPurchase({ obfuscatedExternalAccountId: token, purchaseType: 0 }));
    expect(reply.body).toEqual({ balance: 55, credited: 50, duplicate: false });
  });

  it("rejects a license-tester purchase once test purchases are switched off", async () => {
    const strict = { ...testEnv, ALLOW_GOOGLE_TEST_PURCHASES: "0" };
    const other = crypto.randomUUID();
    const rejected = await submit(
      other,
      { packageName: PKG, productId: OPS50, purchaseToken: playPurchase({ obfuscatedExternalAccountId: other, purchaseType: 0 }) },
      strict,
    );
    expect(rejected.status).toBe(403);
    expect(rejected.body.error.type).toBe("test_purchase_rejected");
    expect(await balanceOf(other)).toEqual({ balance: 5, purchased: false });
    // The pure rule, for completeness: real (absent) and promo purchases are unaffected.
    expect(purchaseVerdict({ purchaseState: 0, obfuscatedExternalAccountId: other }, other, strict)).toBeNull();
    expect(purchaseVerdict({ purchaseState: 0, obfuscatedExternalAccountId: other, purchaseType: 1 }, other, strict)).toBeNull();
  });

  it("validates the request before calling Google", async () => {
    const token = crypto.randomUUID();
    const purchaseToken = playPurchase({ obfuscatedExternalAccountId: token });
    const unknownProduct = await buy(token, purchaseToken, "uk.co.promptbuilt.hobpad.ops9999");
    expect(unknownProduct.status).toBe(422);
    expect(unknownProduct.body.error.type).toBe("bad_request");
    const wrongPackage = await submit(token, { packageName: "com.example.other", productId: OPS50, purchaseToken });
    expect(wrongPackage.status).toBe(422);
    expect(wrongPackage.body.error.type).toBe("bad_request");
    expect((await submit(token, { packageName: PKG, productId: OPS50, purchaseToken: "" })).status).toBe(422);
    expect((await submit(token, { packageName: PKG, productId: OPS50, purchaseToken: "x".repeat(4097) })).status).toBe(422);
    expect((await submit(token, { packageName: PKG, productId: "toString", purchaseToken })).status).toBe(422);
  });

  it("requires a bearer account token", async () => {
    const reply = await submit(null, { packageName: PKG, productId: OPS50, purchaseToken: "pt" });
    expect(reply.status).toBe(401);
    expect((await submit("not-a-uuid", { packageName: PKG, productId: OPS50, purchaseToken: "pt" })).status).toBe(401);
  });

  it("maps a token Google does not know (404) to 422 invalid_purchase", async () => {
    const reply = await buy(crypto.randomUUID(), "pt-never-issued");
    expect(reply.status).toBe(422);
    expect(reply.body.error.type).toBe("invalid_purchase");
  });

  it("maps Google 401/403 to 502 upstream_auth and 5xx to 502 upstream_unavailable", async () => {
    const token = crypto.randomUUID();
    const purchaseToken = playPurchase({ obfuscatedExternalAccountId: token });
    productsStatusOverride = 403;
    expect((await buy(token, purchaseToken)).body.error.type).toBe("upstream_auth");
    productsStatusOverride = 503;
    const outage = await buy(token, purchaseToken);
    expect(outage.status).toBe(502);
    expect(outage.body.error.type).toBe("upstream_unavailable");
    productsStatusOverride = null;
    resetGoogleAuthCache();
    tokenEndpointStatus = 401;
    expect((await buy(token, purchaseToken)).body.error.type).toBe("upstream_auth");
    tokenEndpointStatus = 200;
    expect((await buy(token, purchaseToken)).body.credited).toBe(50); // nothing was credited before
  });

  it("answers 503 not_configured when the service-account secret is missing", async () => {
    const token = crypto.randomUUID();
    const reply = await SELF.fetch("https://ops.test/v1/purchase/google", {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
      body: JSON.stringify({ packageName: PKG, productId: OPS50, purchaseToken: "pt" }),
    });
    expect(reply.status).toBe(503);
    expect((await reply.json()).error.type).toBe("not_configured");
  });
});

describe("Google refunds (Voided Purchases cron)", () => {
  const ordersStub = () => env.GOOGLE_ORDERS.get(env.GOOGLE_ORDERS.idFromName("orders"));

  it("debits a voided purchase once across repeated runs, skipping unknown orders, following pages", async () => {
    const token = crypto.randomUUID();
    const purchaseToken = playPurchase({ obfuscatedExternalAccountId: token });
    await buy(token, purchaseToken, OPS50);
    expect((await balanceOf(token)).balance).toBe(55);
    // Spend some, so the refund drives the balance negative: 55 - 40 - 50 = -35.
    const account = env.OPS.get(env.OPS.idFromName(token));
    expect((await account.reserve("op-g", 40)).ok).toBe(true);
    await account.settle("op-g", true);

    const voided = { orderId: orderIdOf(purchaseToken), purchaseToken, voidedTimeMillis: String(Date.now()), voidedSource: 1, voidedReason: 1 };
    const unknown = { orderId: "GPA.0000-0000-0000-00000", purchaseToken: "pt-other-app", voidedTimeMillis: String(Date.now()) };
    const page = (entries, nextPageToken) => ({
      status: 200,
      body: { voidedPurchases: entries, ...(nextPageToken ? { tokenPagination: { nextPageToken } } : {}) },
    });

    voidedRequests.length = 0;
    voidedPages.push(page([unknown], "page-2"), page([voided]));
    await runCron();
    expect(voidedRequests.length).toBe(2);
    expect(voidedRequests[0].get("maxResults")).toBe("1000");
    expect(Number(voidedRequests[0].get("startTime"))).toBeGreaterThan(Date.now() - 30 * 24 * 3600 * 1000);
    expect(voidedRequests[1].get("token")).toBe("page-2");
    expect(await balanceOf(token)).toEqual({ balance: -35, purchased: true });

    const checkpoint = await ordersStub().checkpoint();
    expect(checkpoint).toBeGreaterThan(Date.now() - 60_000);

    // Google reports the same refund again on the next pass: nothing more is taken.
    voidedPages.push(page([voided, unknown]));
    await runCron();
    expect((await balanceOf(token)).balance).toBe(-35);
  });

  it("keeps the checkpoint when a pass fails, and does nothing when unconfigured", async () => {
    const orders = ordersStub();
    await orders.setCheckpoint(12345);
    voidedPages.push({ status: 500, body: { error: { code: 500 } } });
    await expect(runCron()).rejects.toThrow();
    expect(await orders.checkpoint()).toBe(12345);
    await runCron(env); // no secret: no Google call (an unexpected one would throw)
    expect(await orders.checkpoint()).toBe(12345);
  });
});
