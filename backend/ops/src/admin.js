// Erasing an install's server-side data on request (the right to be forgotten). There is no
// self-service route: people ask through hobpad.app's support form, and Chris runs this call
// with the ADMIN_TOKEN secret (scripts/ops-erase.sh). The app's own notes, todos and recipes
// never reach the service; uninstalling the app removes them.
//
//   POST /v1/admin/erase   Authorization: Bearer <ADMIN_TOKEN>
//     { "token": "<account uuid>" }            erase that account
//     { "googleOrderId": "GPA.1234-…" }        find the account a Google Play order credited
//     { "appleOrderId": "MT3BQRK2W5" }         find it from the order ID on an App Store
//                                              receipt, through the App Store Server API
//   -> 200 { erased: true, googleOrders: n }   404 when nothing is held for it
//
// What erasing removes: the account object (balance, conversion and purchase records, naming
// count) and its entries in the Google order index. What it cannot touch: the per-device
// "had its free credits" bit kept by Apple or Google (src/starter.js), which holds nothing
// about the person and keeps the free credits from being granted to that device again;
// Apple's and Google's own purchase records; and Cloudflare's short-lived request logs, which
// never contain account tokens (index.js logs only an operation name and a status).

import { accountStub, isUuid } from "./account-stub.js";
import { es256Jwt } from "./es256.js";

// App Store Server API "Look Up Order ID". Needs an In-App Purchase key from App Store Connect:
// secret APPLE_IAP_PRIVATE_KEY (.p8 PEM), vars APPLE_IAP_KEY_ID and APPLE_ISSUER_ID.
const APPLE_LOOKUP = [
  "https://api.storekit.itunes.apple.com/inApps/v1/lookup/",
  "https://api.storekit-sandbox.itunes.apple.com/inApps/v1/lookup/",
];

/**
 * The account an App Store order credited, or null. The transactions come straight from Apple,
 * over TLS, in answer to our own authenticated request, so their payloads are read without
 * re-verifying Apple's signature here; the account identifier is the appAccountToken the app
 * set on every purchase (ios/HobPad/Store/OpsStore.swift).
 */
async function appleOrderAccount(env, orderId) {
  if (!env.APPLE_IAP_PRIVATE_KEY || !env.APPLE_IAP_KEY_ID || !env.APPLE_ISSUER_ID) {
    return { error: { status: 503, type: "not_configured", message: "App Store order lookups are not configured yet." } };
  }
  const iat = Math.floor(Date.now() / 1000);
  const jwt = await es256Jwt(
    env.APPLE_IAP_PRIVATE_KEY,
    { kid: env.APPLE_IAP_KEY_ID, typ: "JWT" },
    { iss: env.APPLE_ISSUER_ID, iat, exp: iat + 600, aud: "appstoreconnect-v1", bid: env.BUNDLE_ID },
  );
  // Ask both environments: an order can only exist in one, and one refusing (for example
  // production before the app is on the App Store) must not hide an answer from the other.
  const failures = [];
  for (const base of APPLE_LOOKUP) {
    let reply;
    try {
      reply = await fetch(`${base}${encodeURIComponent(orderId)}`, { headers: { authorization: `Bearer ${jwt}` } });
    } catch {
      failures.push("unreachable");
      continue;
    }
    if (reply.status === 404) continue;
    if (!reply.ok) {
      failures.push(reply.status);
      continue;
    }
    const body = await reply.json().catch(() => ({}));
    for (const jws of body.status === 0 ? body.signedTransactions ?? [] : []) {
      try {
        const token = JSON.parse(Buffer.from(String(jws).split(".")[1], "base64url").toString("utf8")).appAccountToken;
        if (isUuid(token)) return { token };
      } catch {
        // An unreadable transaction cannot name an account; try the next one.
      }
    }
  }
  if (failures.length) {
    console.log(JSON.stringify({ op: "apple_order_lookup", failures }));
    if (failures.length === APPLE_LOOKUP.length) {
      return { error: { status: 502, type: "upstream", message: "The App Store could not be asked about that order." } };
    }
  }
  return { token: null };
}

const ordersStub = (env) => env.GOOGLE_ORDERS.get(env.GOOGLE_ORDERS.idFromName("orders"));

/**
 * Whether the request carries the admin secret. Both sides are hashed first so the comparison
 * is over equal-length values, and timingSafeEqual keeps it from leaking through timing.
 */
export async function isAdmin(request, env) {
  const header = request.headers.get("authorization") || "";
  const given = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!env.ADMIN_TOKEN || !given) return false;
  const digest = async (text) => crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return crypto.subtle.timingSafeEqual(await digest(given), await digest(env.ADMIN_TOKEN));
}

/** Returns { status, body }. */
export async function eraseAccount(env, body) {
  let token = body?.token;
  if (token === undefined && typeof body?.googleOrderId === "string" && body.googleOrderId) {
    const txnId = `google:${body.googleOrderId.trim()}`;
    token = (await ordersStub(env).lookup([txnId]))[txnId];
    if (!token) return { status: 404, body: { error: { type: "not_found", message: "No account was credited by that order." } } };
  }
  if (token === undefined && typeof body?.appleOrderId === "string" && body.appleOrderId) {
    const found = await appleOrderAccount(env, body.appleOrderId.trim());
    if (found.error) return { status: found.error.status, body: { error: { type: found.error.type, message: found.error.message } } };
    if (!found.token) return { status: 404, body: { error: { type: "not_found", message: "No account was credited by that order." } } };
    token = found.token;
  }
  if (!isUuid(token)) return { status: 422, body: { error: { type: "bad_request", message: "Give a token, a googleOrderId or an appleOrderId." } } };
  const result = await accountStub(env, token).erase();
  if (result.googleTxnIds.length) await ordersStub(env).forget(result.googleTxnIds);
  if (!result.existed) return { status: 404, body: { error: { type: "not_found", message: "Nothing is held for that account." } } };
  console.log(JSON.stringify({ op: "erase", googleOrders: result.googleTxnIds.length }));
  return { status: 200, body: { erased: true, googleOrders: result.googleTxnIds.length } };
}
