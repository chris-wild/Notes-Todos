// Erasing an install's server-side data on request (the right to be forgotten). There is no
// self-service route: people ask through hobpad.app's support form, and Chris runs this call
// with the ADMIN_TOKEN secret (scripts/ops-erase.sh). The app's own notes, todos and recipes
// never reach the service; uninstalling the app removes them.
//
//   POST /v1/admin/erase   Authorization: Bearer <ADMIN_TOKEN>
//     { "token": "<account uuid>" }            erase that account
//     { "googleOrderId": "GPA.1234-…" }        find the account a Google Play order credited
//   -> 200 { erased: true, googleOrders: n }   404 when nothing is held for it
//
// What erasing removes: the account object (balance, conversion and purchase records, naming
// count) and its entries in the Google order index. What it cannot touch: the per-device
// "had its free credits" bit kept by Apple or Google (src/starter.js), which holds nothing
// about the person and keeps the free credits from being granted to that device again;
// Apple's and Google's own purchase records; and Cloudflare's short-lived request logs, which
// never contain account tokens (index.js logs only an operation name and a status).

import { accountStub, isUuid } from "./account-stub.js";

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
  if (!isUuid(token)) return { status: 422, body: { error: { type: "bad_request", message: "Give a token or a googleOrderId." } } };
  const result = await accountStub(env, token).erase();
  if (result.googleTxnIds.length) await ordersStub(env).forget(result.googleTxnIds);
  if (!result.existed) return { status: 404, body: { error: { type: "not_found", message: "Nothing is held for that account." } } };
  console.log(JSON.stringify({ op: "erase", googleOrders: result.googleTxnIds.length }));
  return { status: 200, body: { erased: true, googleOrders: result.googleTxnIds.length } };
}
