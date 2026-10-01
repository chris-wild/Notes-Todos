// Google Play purchase verification and refunds, the Android counterpart of src/purchase.js.
// Unlike Apple's signed JWS, a Play purchase token proves nothing by itself: the purchase is
// read back from the Google Play Developer API with a service-account credential (secret
// GOOGLE_SERVICE_ACCOUNT_JSON, the standard key JSON). Refunds have no push channel wired
// here; the cron handler polls the Voided Purchases API instead.
//
// CPU budget (Workers Free, ~10 ms): the RS256 assertion is signed with WebCrypto, which runs
// natively, and the access token it buys is cached per isolate until shortly before expiry,
// so most purchases cost two small fetches and no signing at all.

import { accountStub } from "./account-stub.js";
import { productCredits } from "./purchase.js";

const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const MAX_PURCHASE_TOKEN = 4096;
const DAY_MS = 24 * 60 * 60 * 1000;
// Google refuses a voided-purchases startTime older than 30 days; the margin absorbs clock skew.
const VOIDED_WINDOW_MS = 30 * DAY_MS - 10 * 60 * 1000;
// Google does not document how soon a refund becomes visible to the API, so each pass re-reads
// a day before its checkpoint. Re-reading is free: refundPurchase() debits a transaction once.
const VOIDED_OVERLAP_MS = DAY_MS;

class GoogleError extends Error {
  constructor(status, type, message) {
    super(message);
    this.status = status;
    this.type = type;
  }
}

const failure = (status, type, message) => ({ status, body: { error: { type, message } } });

// ---- service-account access token ---------------------------------------------------------

let cachedToken = null; // { email, accessToken, expiresAt }
let cachedKey = null; // { pem, key }

/** Tests mint a fresh token deliberately; production never needs to. */
export function resetGoogleAuthCache() {
  cachedToken = null;
  cachedKey = null;
}

function serviceAccount(env) {
  if (!env.GOOGLE_SERVICE_ACCOUNT_JSON) return null;
  try {
    const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON);
    return account?.client_email && account?.private_key ? account : null;
  } catch {
    return null;
  }
}

async function signingKey(pem) {
  if (cachedKey?.pem === pem) return cachedKey.key;
  const der = Buffer.from(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64");
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  cachedKey = { pem, key };
  return key;
}

const b64url = (data) => Buffer.from(typeof data === "string" ? data : new Uint8Array(data)).toString("base64url");

async function accessToken(env) {
  const account = serviceAccount(env);
  if (!account) throw new GoogleError(503, "not_configured", "Google Play verification is not configured.");
  const now = Date.now();
  if (cachedToken?.email === account.client_email && cachedToken.expiresAt - 60_000 > now) return cachedToken.accessToken;

  let key;
  try {
    key = await signingKey(account.private_key);
  } catch {
    throw new GoogleError(503, "not_configured", "Google Play verification is not configured.");
  }
  const iat = Math.floor(now / 1000);
  const signingInput =
    b64url(JSON.stringify({ alg: "RS256", typ: "JWT" })) +
    "." +
    b64url(JSON.stringify({ iss: account.client_email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 }));
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(signingInput));

  let reply;
  try {
    reply = await fetch(TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: `${signingInput}.${b64url(signature)}`,
      }).toString(),
    });
  } catch {
    throw new GoogleError(502, "upstream_unavailable", "Google Play could not be reached.");
  }
  if (reply.status >= 500) throw new GoogleError(502, "upstream_unavailable", "Google Play could not be reached.");
  const body = reply.ok ? await reply.json().catch(() => null) : null;
  if (!body?.access_token) {
    console.log(JSON.stringify({ op: "google_token", status: reply.status, note: "service account refused" }));
    throw new GoogleError(502, "upstream_auth", "The purchase service is misconfigured. Please try again later.");
  }
  cachedToken = {
    email: account.client_email,
    accessToken: body.access_token,
    expiresAt: now + Number(body.expires_in || 3600) * 1000,
  };
  return cachedToken.accessToken;
}

/**
 * GET from the Play Developer API. Our-credential failures (401/403) and outages (network,
 * 429, 5xx) throw; every other status is returned for the caller to interpret.
 */
async function playGet(env, url) {
  const token = await accessToken(env);
  let reply;
  try {
    reply = await fetch(url, { headers: { authorization: `Bearer ${token}` } });
  } catch {
    throw new GoogleError(502, "upstream_unavailable", "Google Play could not be reached.");
  }
  if (reply.status === 401 || reply.status === 403) {
    cachedToken = null;
    console.log(JSON.stringify({ op: "google_api", status: reply.status, note: "upstream auth failed" }));
    throw new GoogleError(502, "upstream_auth", "The purchase service is misconfigured. Please try again later.");
  }
  if (reply.status === 429 || reply.status >= 500) {
    throw new GoogleError(502, "upstream_unavailable", "Google Play could not be reached.");
  }
  return reply;
}

// ---- purchase crediting -------------------------------------------------------------------

const ordersStub = (env) => env.GOOGLE_ORDERS.get(env.GOOGLE_ORDERS.idFromName("orders"));

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Buffer.from(digest).toString("hex");
}

/**
 * The OpsAccount transaction id of a Play purchase, namespaced so it can never collide with an
 * Apple transaction id. Google documents orderId as absent "in certain purchase scenarios";
 * those fall back to a hash of the purchase token so they still credit exactly once.
 */
export async function googleTxnId(orderId, purchaseToken) {
  if (orderId) return `google:${orderId}`;
  return `google:pt:${await sha256Hex(String(purchaseToken))}`;
}

/**
 * The rules a VERIFIED Play purchase must pass before it may credit [token]'s account.
 * Returns null when it may, or the { status, body } refusal.
 */
export function purchaseVerdict(purchase, token, env) {
  if (purchase.purchaseState === 2) {
    return failure(409, "pending", "The purchase is still pending. Keep it and try again later.");
  }
  if (purchase.purchaseState !== 0) return failure(422, "invalid_purchase", "The purchase was cancelled.");
  if (String(purchase.obfuscatedExternalAccountId || "").toLowerCase() !== token.toLowerCase()) {
    return failure(403, "account_mismatch", "The purchase belongs to a different account.");
  }
  if (purchase.purchaseType === 0 && env.ALLOW_GOOGLE_TEST_PURCHASES !== "1") {
    return failure(403, "test_purchase_rejected", "Test purchases are not accepted.");
  }
  return null;
}

/**
 * POST /v1/purchase/google. [token] is the bearer (already UUID-checked by the router); the
 * purchase must carry it as obfuscatedExternalAccountId, which the app set at purchase time.
 * Returns { status, body } for the route to send.
 */
export async function creditGooglePurchase(env, token, request) {
  const { packageName, productId, purchaseToken } = request && typeof request === "object" ? request : {};
  if (!env.PLAY_PACKAGE_NAME) return failure(503, "not_configured", "Google Play verification is not configured.");
  if (packageName !== env.PLAY_PACKAGE_NAME) return failure(422, "bad_request", "Unknown packageName.");
  const credits = productCredits(env);
  if (typeof productId !== "string" || !Object.hasOwn(credits, productId)) {
    return failure(422, "bad_request", "Unknown productId.");
  }
  if (typeof purchaseToken !== "string" || purchaseToken === "" || purchaseToken.length > MAX_PURCHASE_TOKEN) {
    return failure(422, "bad_request", "purchaseToken must be a non-empty string.");
  }

  let purchase;
  try {
    const url =
      `${API}/${encodeURIComponent(packageName)}/purchases/products/` +
      `${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
    const reply = await playGet(env, url);
    if (reply.status === 400 || reply.status === 404 || reply.status === 410) {
      return failure(422, "invalid_purchase", "Google Play does not recognise this purchase.");
    }
    if (!reply.ok) return failure(502, "upstream_unavailable", "Google Play could not be reached.");
    purchase = await reply.json();
  } catch (e) {
    if (e instanceof GoogleError) return failure(e.status, e.type, e.message);
    throw e;
  }
  if (purchase.productId && purchase.productId !== productId) {
    return failure(422, "invalid_purchase", "The purchase is for a different product.");
  }

  const refusal = purchaseVerdict(purchase, token, env);
  if (refusal) return refusal;

  const txnId = await googleTxnId(purchase.orderId, purchaseToken);
  const quantity = Number.isInteger(purchase.quantity) && purchase.quantity > 0 ? purchase.quantity : 1;
  // Index first: if crediting then fails, the client retries and the index write is a no-op;
  // the reverse order could leave a credited purchase that a refund can never find.
  await ordersStub(env).record(txnId, token.toLowerCase());
  const result = await accountStub(env, token).credit(txnId, credits[productId] * quantity, {
    platform: "google",
    productId,
    orderId: purchase.orderId ?? null,
    purchaseType: purchase.purchaseType ?? null,
  });
  return { status: 200, body: result };
}

// ---- refunds (cron) -----------------------------------------------------------------------

/**
 * One pass over Google's Voided Purchases API: every voided purchase this Worker credited is
 * taken back from its account (negative balances allowed, once per transaction). The
 * checkpoint advances only when the whole pass succeeded. Missing configuration is a no-op.
 */
export async function checkVoidedPurchases(env) {
  if (!serviceAccount(env) || !env.PLAY_PACKAGE_NAME) return { skipped: "not_configured" };
  const orders = ordersStub(env);
  const passStart = Date.now();
  const checkpoint = await orders.checkpoint();
  const startTime = Math.max(passStart - VOIDED_WINDOW_MS, (checkpoint ?? 0) - VOIDED_OVERLAP_MS);

  let pageToken = null;
  let voided = 0;
  let debited = 0;
  do {
    const url = new URL(`${API}/${encodeURIComponent(env.PLAY_PACKAGE_NAME)}/purchases/voidedpurchases`);
    url.searchParams.set("startTime", String(startTime));
    url.searchParams.set("maxResults", "1000");
    if (pageToken) url.searchParams.set("token", pageToken);
    const reply = await playGet(env, url.toString());
    if (!reply.ok) throw new GoogleError(502, "upstream_unavailable", `voidedpurchases answered ${reply.status}`);
    const page = await reply.json();
    const entries = page.voidedPurchases || [];
    voided += entries.length;

    const txnIds = [];
    for (const entry of entries) {
      if (entry.orderId || entry.purchaseToken) txnIds.push(await googleTxnId(entry.orderId, entry.purchaseToken));
    }
    const accounts = txnIds.length ? await orders.lookup(txnIds) : {};
    for (const txnId of txnIds) {
      const owner = accounts[txnId];
      if (!owner) continue; // not ours to take back (or credited by another deployment)
      debited += (await accountStub(env, owner).refundPurchase(txnId)).debited;
    }
    pageToken = page.tokenPagination?.nextPageToken || null;
  } while (pageToken);

  await orders.setCheckpoint(passStart);
  return { voided, debited };
}
