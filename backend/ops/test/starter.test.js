// Free starter credits once per device (src/starter.js) and deletion requests (src/admin.js),
// through the real Worker. Apple DeviceCheck, Google's token endpoint and the Play Integrity API
// are stubbed through the global fetch; the DeviceCheck stub VERIFIES each ES256 token against
// a throwaway key, so a broken signer fails here. Per-device bits live in the stubs' maps, as
// Apple and Google would hold them.

import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import worker from "../src/index.js";
import { resetGoogleAuthCache } from "../src/google.js";
import { resetDeviceCheckCache, starterNonce } from "../src/starter.js";

const PKG = "uk.co.promptbuilt.hobpad";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const ACCESS_TOKEN = "ya29.starter-test";
const DC_PROD = "https://api.devicecheck.apple.com/v1/";
const DC_DEV = "https://api.development.devicecheck.apple.com/v1/";
const PI = "https://playintegrity.googleapis.com/v1/";
const ADMIN = "admin-secret-for-tests";
const APPLE_LOOKUP = "https://api.storekit.itunes.apple.com/inApps/v1/lookup/";
const APPLE_LOOKUP_SANDBOX = "https://api.storekit-sandbox.itunes.apple.com/inApps/v1/lookup/";
// App Store order id -> { environment, appAccountToken } served by the lookup stubs.
const appleOrders = new Map();
const appleLookups = [];

const realFetch = globalThis.fetch;
const jsonReply = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

let dcPublicKey;
let testEnv;
// DeviceCheck: device token -> bit0, per environment; and every call made.
const appleBits = { production: new Map(), development: new Map() };
const appleCalls = [];
let appleStatus = 200;
// Play Integrity: integrity token -> decoded payload; device recall bits by device name.
const integrityTokens = new Map();
const googleBits = new Map();
const recallWrites = [];

async function verifyEs256(jwt) {
  const [header, claims, signature] = jwt.split(".");
  const ok = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    dcPublicKey,
    Buffer.from(signature, "base64url"),
    new TextEncoder().encode(`${header}.${claims}`),
  );
  return { ok, header: JSON.parse(Buffer.from(header, "base64url")), claims: JSON.parse(Buffer.from(claims, "base64url")) };
}

beforeAll(async () => {
  const ec = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  dcPublicKey = ec.publicKey;
  const ecPkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", ec.privateKey)).toString("base64");
  const rsa = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  const rsaPkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", rsa.privateKey)).toString("base64");
  const pem = (b64) => `-----BEGIN PRIVATE KEY-----\n${b64.match(/.{1,64}/g).join("\n")}\n-----END PRIVATE KEY-----\n`;
  testEnv = {
    ...env,
    DEVICECHECK_PRIVATE_KEY: pem(ecPkcs8),
    GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify({ type: "service_account", client_email: "sa@hobpad-test.iam.gserviceaccount.com", private_key: pem(rsaPkcs8) }),
    ADMIN_TOKEN: ADMIN,
    APPLE_IAP_PRIVATE_KEY: pem(ecPkcs8),
    APPLE_IAP_KEY_ID: "IAPKEY1234",
    APPLE_ISSUER_ID: "issuer-uuid",
  };

  globalThis.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    if (url === TOKEN_URL) return jsonReply(200, { access_token: ACCESS_TOKEN, expires_in: 3599 });
    if (url.startsWith(DC_PROD) || url.startsWith(DC_DEV)) {
      const environment = url.startsWith(DC_PROD) ? "production" : "development";
      const auth = await verifyEs256(init.headers.authorization.slice("Bearer ".length));
      const body = JSON.parse(init.body);
      appleCalls.push({ environment, endpoint: url.split("/").pop(), auth, body });
      if (!auth.ok) return new Response("Invalid Authorization Token", { status: 401 });
      if (appleStatus !== 200) return new Response("Service Unavailable", { status: appleStatus });
      if (!body.device_token.startsWith("dev-")) return new Response("Bad Device Token", { status: 400 });
      const bits = appleBits[environment];
      if (url.endsWith("/query_two_bits")) {
        if (!bits.has(body.device_token)) {
          return new Response(body.device_token.endsWith("-old") ? "Failed to find bit state" : "Bit State Not Found", { status: 200 });
        }
        return jsonReply(200, { bit0: bits.get(body.device_token), bit1: false, last_update_time: "2026-10" });
      }
      if (url.endsWith("/update_two_bits")) {
        bits.set(body.device_token, body.bit0);
        return new Response("", { status: 200 });
      }
    }
    if (url.startsWith(APPLE_LOOKUP) || url.startsWith(APPLE_LOOKUP_SANDBOX)) {
      const environment = url.startsWith(APPLE_LOOKUP) ? "Production" : "Sandbox";
      const auth = await verifyEs256(init.headers.authorization.slice("Bearer ".length));
      appleLookups.push({ environment, auth });
      if (!auth.ok) return jsonReply(401, {});
      const order = appleOrders.get(decodeURIComponent(url.split("/").pop()));
      if (!order || order.environment !== environment) return jsonReply(200, { status: 1 });
      const jws = `${Buffer.from("{}").toString("base64url")}.${Buffer.from(JSON.stringify({ appAccountToken: order.token })).toString("base64url")}.sig`;
      return jsonReply(200, { status: 0, signedTransactions: [jws] });
    }
    if (url.startsWith(PI)) {
      if (init?.headers?.authorization !== `Bearer ${ACCESS_TOKEN}`) return jsonReply(401, { error: { code: 401 } });
      const body = JSON.parse(init.body);
      if (url === `${PI}${PKG}:decodeIntegrityToken`) {
        const entry = integrityTokens.get(body.integrity_token);
        if (!entry) return jsonReply(400, { error: { code: 400, message: "invalid token" } });
        const payload = structuredClone(entry.payload);
        const recall = payload.deviceIntegrity?.deviceRecall;
        if (recall && entry.device && recall.values?.bitFirst !== undefined) {
          recall.values.bitFirst = googleBits.get(entry.device) === true;
        }
        return jsonReply(200, { tokenPayloadExternal: payload });
      }
      if (url === `${PI}${PKG}/deviceRecall:write`) {
        const entry = integrityTokens.get(body.integrityToken);
        recallWrites.push(body);
        if (!entry) return jsonReply(400, { error: { code: 400 } });
        googleBits.set(entry.device, body.newValues.bitFirst);
        return jsonReply(200, {});
      }
    }
    return realFetch(input, init);
  };
});
afterAll(() => {
  globalThis.fetch = realFetch;
});
beforeEach(() => {
  resetGoogleAuthCache();
  resetDeviceCheckCache();
  appleStatus = 200;
  appleCalls.length = 0;
  recallWrites.length = 0;
});

async function call(path, { token, method = "GET", body, checked = true, e = testEnv, headers = {} } = {}) {
  const request = new Request(`https://ops.test${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(checked ? { "x-hobpad-starter": "1" } : {}),
      ...(body !== undefined ? { "content-type": "application/json" } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const reply = await worker.fetch(request, e);
  return { status: reply.status, body: await reply.json() };
}

const balance = (token, opts) => call("/v1/balance", { token, ...opts });
const starter = (token, body, opts) => call("/v1/starter", { token, method: "POST", body, ...opts });

/** Register an integrity token as Google would decode it, for [device] and [token]. */
async function integrityToken(token, device, overrides = {}) {
  const integrity = `it-${crypto.randomUUID()}`;
  const payload = {
    requestDetails: { requestPackageName: PKG, nonce: `${await starterNonce(token)}=`, timestampMillis: String(Date.now()) },
    appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: PKG },
    accountDetails: { appLicensingVerdict: "LICENSED" },
    deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"], deviceRecall: { values: { bitFirst: false }, writeDates: {} } },
    ...overrides,
  };
  integrityTokens.set(integrity, { payload, device });
  return integrity;
}

describe("starterNonce", () => {
  // Pinned in app/src/test/.../StarterCheckTest.kt too (there with base64 padding, which the
  // Worker ignores): the two sides must compute the same nonce.
  it("matches the Android client", async () => {
    expect(await starterNonce("1B4E28BA-2FA1-41D2-883F-0016D3CCA427")).toBe("kQDtv7xWYJgVMr2kNTBf6932c9-FHq5YWshJBNSARAQ");
  });
});

describe("accounts from clients that check the device", () => {
  it("start at zero with the starter grant pending", async () => {
    const token = crypto.randomUUID();
    expect((await balance(token)).body).toEqual({ balance: 0, purchased: false, starter: "pending" });
  });

  it("keep the old behaviour for clients without the header while LEGACY_FREE_OPS is on", async () => {
    const token = crypto.randomUUID();
    expect((await balance(token, { checked: false })).body).toEqual({ balance: 5, purchased: false, starter: "granted" });
  });

  it("make every new account wait for a device check once LEGACY_FREE_OPS is off", async () => {
    const token = crypto.randomUUID();
    const reply = await balance(token, { checked: false, e: { ...testEnv, LEGACY_FREE_OPS: "0" } });
    expect(reply.body).toEqual({ balance: 0, purchased: false, starter: "pending" });
  });

  it("wait for a device check when a purchase creates them", async () => {
    const token = crypto.randomUUID();
    const reply = await call("/v1/purchase", {
      method: "POST",
      body: { test: { productId: "uk.co.promptbuilt.hobpad.ops50", transactionId: `t-${crypto.randomUUID()}`, appAccountToken: token } },
    });
    expect(reply.body.balance).toBe(50);
    expect((await balance(token)).body).toEqual({ balance: 50, purchased: true, starter: "pending" });
  });
});

describe("POST /v1/starter, staging test devices", () => {
  it("grants the free credits once, and a second claim changes nothing", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    expect((await starter(token, { platform: "test" })).body).toEqual({ starter: "granted", balance: 5 });
    expect((await starter(token, { platform: "test" })).body).toEqual({ starter: "granted", balance: 5 });
    expect((await balance(token)).body.balance).toBe(5);
  });

  it("denies a device that already had them", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    expect((await starter(token, { platform: "test", used: true })).body).toEqual({ starter: "denied", balance: 0 });
  });

  it("is refused in production", async () => {
    const token = crypto.randomUUID();
    const e = { ...testEnv, TEST_MODE: "0" };
    await balance(token, { e });
    const reply = await starter(token, { platform: "test" }, { e });
    expect(reply.status).toBe(403);
    expect(reply.body.starter).toBe("pending");
  });

  it("never re-grants an account from before device checks", async () => {
    const token = crypto.randomUUID();
    await balance(token, { checked: false });
    expect((await starter(token, { platform: "test" })).body).toEqual({ starter: "granted", balance: 5 });
  });
});

describe("POST /v1/starter on iOS (DeviceCheck)", () => {
  it("grants on a fresh device, sets bit0, and denies the same device for a new account", async () => {
    const first = crypto.randomUUID();
    await balance(first);
    const reply = await starter(first, { platform: "ios", deviceToken: "dev-iphone-1", environment: "production" });
    expect(reply.body).toEqual({ starter: "granted", balance: 5 });
    expect(appleBits.production.get("dev-iphone-1")).toBe(true);
    expect(appleCalls.map((c) => c.endpoint)).toEqual(["query_two_bits", "update_two_bits"]);
    expect(appleCalls[0].auth.header).toEqual({ alg: "ES256", kid: "6SX2P37597" });
    expect(appleCalls[0].auth.claims.iss).toBe("U9RDY3V58L");
    expect(appleCalls[1].body.bit0).toBe(true);

    const second = crypto.randomUUID();
    await balance(second);
    expect((await starter(second, { platform: "ios", deviceToken: "dev-iphone-1", environment: "production" })).body)
      .toEqual({ starter: "denied", balance: 0 });
  });

  it("reads the older 'Failed to find bit state' wording as never set", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    expect((await starter(token, { platform: "ios", deviceToken: "dev-iphone-old", environment: "production" })).body)
      .toEqual({ starter: "granted", balance: 5 });
  });

  it("uses Apple's development server only on staging", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    await starter(token, { platform: "ios", deviceToken: "dev-iphone-2", environment: "development" });
    expect(appleCalls[0].environment).toBe("development");

    appleCalls.length = 0;
    const e = { ...testEnv, TEST_MODE: "0" };
    const other = crypto.randomUUID();
    await balance(other, { e });
    await starter(other, { platform: "ios", deviceToken: "dev-iphone-3", environment: "development" }, { e });
    expect(appleCalls[0].environment).toBe("production");
  });

  it("answers a staging probe without writing or granting, and refuses probes in production", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    appleBits.production.set("dev-iphone-probe", true);
    const reply = await starter(token, { platform: "ios", deviceToken: "dev-iphone-probe", environment: "production", probe: true });
    expect(reply.body).toEqual({ probe: true, used: true });
    expect(appleCalls.map((c) => c.endpoint)).toEqual(["query_two_bits"]);
    expect((await balance(token)).body.starter).toBe("pending");
    const e = { ...testEnv, TEST_MODE: "0" };
    expect((await starter(token, { platform: "ios", deviceToken: "dev-iphone-probe", probe: true }, { e })).status).toBe(403);
  });

  it("stays pending when Apple is unavailable", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    appleStatus = 503;
    const reply = await starter(token, { platform: "ios", deviceToken: "dev-iphone-4", environment: "production" });
    expect(reply.status).toBe(503);
    expect(reply.body.starter).toBe("pending");
    appleStatus = 200;
    expect((await starter(token, { platform: "ios", deviceToken: "dev-iphone-4", environment: "production" })).body.starter).toBe("granted");
  });

  it("refuses a bad device token without settling the account", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    const reply = await starter(token, { platform: "ios", deviceToken: "forged", environment: "production" });
    expect(reply.status).toBe(422);
    expect((await balance(token)).body.starter).toBe("pending");
  });
});

describe("POST /v1/starter on Android (Play Integrity device recall)", () => {
  it("grants on a fresh device, writes bitFirst, and denies the same device for a new account", async () => {
    const first = crypto.randomUUID();
    await balance(first);
    const reply = await starter(first, { platform: "android", integrityToken: await integrityToken(first, "pixel-1") });
    expect(reply.body).toEqual({ starter: "granted", balance: 5 });
    expect(recallWrites).toHaveLength(1);
    expect(recallWrites[0].newValues).toEqual({ bitFirst: true });

    const second = crypto.randomUUID();
    await balance(second);
    expect((await starter(second, { platform: "android", integrityToken: await integrityToken(second, "pixel-1") })).body)
      .toEqual({ starter: "denied", balance: 0 });
    expect(recallWrites).toHaveLength(1);
  });

  it("refuses a token made for another account", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    const reply = await starter(token, { platform: "android", integrityToken: await integrityToken(crypto.randomUUID(), "pixel-2") });
    expect(reply.status).toBe(422);
    expect(reply.body.starter).toBe("pending");
  });

  it("refuses a stale token", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    const stale = await integrityToken(token, "pixel-3", {
      requestDetails: { requestPackageName: PKG, nonce: await starterNonce(token), timestampMillis: String(Date.now() - 60 * 60 * 1000) },
    });
    expect((await starter(token, { platform: "android", integrityToken: stale })).status).toBe(422);
  });

  it("leaves an unrecognised app or failing device pending", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    const sideloaded = await integrityToken(token, "pixel-4", { appIntegrity: { appRecognitionVerdict: "UNRECOGNIZED_VERSION" } });
    const reply = await starter(token, { platform: "android", integrityToken: sideloaded });
    expect(reply.status).toBe(403);
    expect((await balance(token)).body).toEqual({ balance: 0, purchased: false, starter: "pending" });
    expect(recallWrites).toHaveLength(0);
  });

  it("waits while device recall is unavailable", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    const noRecall = await integrityToken(token, "pixel-5", {
      deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"], deviceRecall: { values: {}, writeDates: {} } },
    });
    const reply = await starter(token, { platform: "android", integrityToken: noRecall });
    expect(reply.status).toBe(503);
    expect(reply.body.starter).toBe("pending");
    expect(recallWrites).toHaveLength(0);
  });
});

describe("POST /v1/admin/erase", () => {
  const erase = (body, secret = ADMIN) => call("/v1/admin/erase", { method: "POST", body, headers: { authorization: `Bearer ${secret}` }, checked: false });

  it("needs the admin secret", async () => {
    expect((await erase({ token: crypto.randomUUID() }, "wrong")).status).toBe(401);
    expect((await call("/v1/admin/erase", { method: "POST", body: {}, checked: false, e: { ...testEnv, ADMIN_TOKEN: undefined } })).status).toBe(401);
  });

  it("erases an account; the same identifier later starts again pending, and the device bit still denies it", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    await starter(token, { platform: "ios", deviceToken: "dev-iphone-erase", environment: "production" });
    expect((await balance(token)).body.balance).toBe(5);

    expect((await erase({ token })).body).toEqual({ erased: true, googleOrders: 0 });
    expect((await erase({ token })).status).toBe(404);

    expect((await balance(token)).body).toEqual({ balance: 0, purchased: false, starter: "pending" });
    expect((await starter(token, { platform: "ios", deviceToken: "dev-iphone-erase", environment: "production" })).body)
      .toEqual({ starter: "denied", balance: 0 });
  });

  it("finds an account by App Store order id, trying production then sandbox", async () => {
    const token = crypto.randomUUID();
    await balance(token);
    appleOrders.set("MT3BQRK2W5", { environment: "Sandbox", token });
    appleLookups.length = 0;
    expect((await erase({ appleOrderId: "MT3BQRK2W5" })).body).toEqual({ erased: true, googleOrders: 0 });
    expect(appleLookups.map((l) => l.environment)).toEqual(["Production", "Sandbox"]);
    expect(appleLookups[0].auth.header).toEqual({ alg: "ES256", kid: "IAPKEY1234", typ: "JWT" });
    expect(appleLookups[0].auth.claims).toMatchObject({ iss: "issuer-uuid", aud: "appstoreconnect-v1", bid: "uk.co.promptbuilt.hobpad" });
    expect((await erase({ appleOrderId: "UNKNOWN1" })).status).toBe(404);
  });

  it("says when App Store order lookups are not configured", async () => {
    const e = { ...testEnv, APPLE_IAP_PRIVATE_KEY: undefined };
    const reply = await call("/v1/admin/erase", { method: "POST", body: { appleOrderId: "MT3BQRK2W5" }, headers: { authorization: `Bearer ${ADMIN}` }, checked: false, e });
    expect(reply.status).toBe(503);
  });

  it("finds an account by Google order id and removes its order index entries", async () => {
    const token = crypto.randomUUID();
    const orderId = `GPA.${crypto.randomUUID()}`;
    const orders = env.GOOGLE_ORDERS.get(env.GOOGLE_ORDERS.idFromName("orders"));
    const account = env.OPS.get(env.OPS.idFromName(token));
    await orders.record(`google:${orderId}`, token);
    await account.credit(`google:${orderId}`, 50, { platform: "google", orderId }, true);

    expect((await erase({ googleOrderId: orderId })).body).toEqual({ erased: true, googleOrders: 1 });
    expect(await orders.lookup([`google:${orderId}`])).toEqual({});
    expect((await erase({ googleOrderId: orderId })).status).toBe(404);
  });
});
