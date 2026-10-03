// Offline App Store JWS verification, no App Store Server API: Apple's own library walks the
// x5c chain to the pinned roots and checks the ES256 signature, bundle id and environment.
// Needs the nodejs_compat flag (the library uses Node crypto's X509Certificate); the
// conformance test in test/purchase.test.js exists to catch a workerd regression there
// before a deploy does.
//
// The library is loaded LAZILY, on the first purchase or notification: its jsrsasign
// dependency draws random values at module scope, which Cloudflare's deploy validation
// forbids in the global scope (error 10021) even though local workerd tolerates it.

import { appleRootCertificates } from "./apple-roots.js";
import { accountStub, isUuid } from "./account-stub.js";

let libPromise;
const appleLib = () => (libPromise ??= import("@apple/app-store-server-library"));

const verifiers = new Map(); // per-isolate cache, keyed by environment

function allowedEnvironments(env) {
  return (env.ALLOWED_ENVIRONMENTS || "").split(",").map((s) => s.trim()).filter(Boolean);
}

/** A verifier for [environment], or null when that environment is not allowed / not configured. */
async function verifierFor(env, environment) {
  if (!allowedEnvironments(env).includes(environment)) return null;
  // The library refuses to verify Production payloads without the numeric App Store id,
  // which only exists once the App Store Connect app record does (secret APP_APPLE_ID).
  const appAppleId = env.APP_APPLE_ID ? Number(env.APP_APPLE_ID) : undefined;
  if (environment === "Production" && !appAppleId) return null;
  if (!verifiers.has(environment)) {
    const { SignedDataVerifier } = await appleLib();
    verifiers.set(
      environment,
      new SignedDataVerifier(appleRootCertificates(), false, environment, env.BUNDLE_ID, appAppleId),
    );
  }
  return verifiers.get(environment);
}

/**
 * The environment claimed by an UNVERIFIED JWS payload — used only to pick which verifier
 * to run; the verifier then proves the claim before anything is trusted.
 */
function peekEnvironment(jws, path) {
  try {
    const payload = JSON.parse(Buffer.from(String(jws).split(".")[1], "base64url").toString("utf8"));
    return path === "notification" ? payload?.data?.environment : payload?.environment;
  } catch {
    return null;
  }
}

export function productCredits(env) {
  return JSON.parse(env.PRODUCT_CREDITS || "{}");
}

/**
 * Verify a purchase JWS and credit the pack. Everything that matters — the account token,
 * the product, the transaction id — is read from inside the VERIFIED payload, never from
 * client-supplied fields. Returns { status, body } for the route to send.
 */
export async function creditPurchase(env, jws, deviceChecked = false) {
  if (typeof jws !== "string" || jws.split(".").length !== 3) return { status: 422, body: { error: "bad_jws" } };
  const environment = peekEnvironment(jws, "transaction");
  const verifier = await verifierFor(env, environment);
  if (!verifier) return { status: 403, body: { error: "environment_not_allowed" } };
  const { VerificationException } = await appleLib();
  let payload;
  try {
    payload = await verifier.verifyAndDecodeTransaction(jws);
  } catch (e) {
    if (e instanceof VerificationException) return { status: 403, body: { error: "verification_failed" } };
    throw e;
  }
  return creditVerified(env, payload, environment, deviceChecked);
}

/** Shared by the real path and the staging-only TEST_MODE path (index.js gates the latter). */
export async function creditVerified(env, payload, environment, deviceChecked = false) {
  const credits = productCredits(env)[payload.productId];
  if (!credits) return { status: 422, body: { error: "unknown_product" } };
  if (payload.type && payload.type !== "Consumable") return { status: 422, body: { error: "not_consumable" } };
  if (!isUuid(payload.appAccountToken)) return { status: 422, body: { error: "missing_account_token" } };
  if (!payload.transactionId) return { status: 422, body: { error: "missing_transaction_id" } };
  const ops = credits * (payload.quantity || 1);
  const result = await accountStub(env, payload.appAccountToken).credit(String(payload.transactionId), ops, {
    productId: payload.productId,
    environment,
  }, deviceChecked);
  return { status: 200, body: result };
}

/**
 * App Store Server Notification V2 (configured in App Store Connect). Only REFUND changes
 * anything: the refunded pack's ops are taken back, negative balances allowed. Everything
 * else is acknowledged and ignored — Apple retries on non-2xx, so unknown types must 200.
 */
export async function applyNotification(env, signedPayload) {
  const environment = peekEnvironment(signedPayload, "notification");
  const verifier = await verifierFor(env, environment);
  if (!verifier) return { status: 403, body: { error: "environment_not_allowed" } };
  const { VerificationException } = await appleLib();
  let notification;
  try {
    notification = await verifier.verifyAndDecodeNotification(signedPayload);
  } catch (e) {
    if (e instanceof VerificationException) return { status: 403, body: { error: "verification_failed" } };
    throw e;
  }
  if (notification.notificationType !== "REFUND") return { status: 200, body: { ignored: notification.notificationType } };
  const txn = await verifier.verifyAndDecodeTransaction(notification.data?.signedTransactionInfo);
  if (!isUuid(txn.appAccountToken)) return { status: 200, body: { ignored: "no_account_token" } };
  const result = await accountStub(env, txn.appAccountToken).refundPurchase(String(txn.transactionId));
  return { status: 200, body: result };
}
