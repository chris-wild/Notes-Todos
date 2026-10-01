// HobPad recipe-conversion metering proxy. Holds the shared Anthropic key (Worker secret
// ANTHROPIC_API_KEY) so it never ships in an app binary; balances live in one Durable
// Object per anonymous account token (src/account.js); purchases are verified offline
// (src/purchase.js). Deployed by scripts/ops-deploy.sh, never by an app build.
//
// Routes (bearer = the app's anonymous account UUID unless noted):
//   GET  /v1/balance                       -> { balance }
//   POST /v1/extract?name=…    base64 PDF  -> Anthropic Messages JSON, charges PAGES ops
//   POST /v1/extract-text?name=…  raw text -> Anthropic Messages JSON, charges 1 op
//   POST /v1/title             base64 PDF  -> Anthropic Messages JSON, FREE (daily-capped)
//   POST /v1/purchase  {jws}   no bearer   -> { balance, credited, duplicate } (verified JWS)
//   POST /v1/asn  {signedPayload}          -> App Store Server Notifications V2 (refunds)
//   POST /v1/purchase/google  {packageName, productId, purchaseToken}
//                                          -> { balance, credited, duplicate } (Play-verified)
//   cron "0 */6 * * *"                     -> Google Play voided purchases (refunds)
//
// PERFORMANCE CONTRACT (Workers Free = 10 ms CPU per request): the multi-megabyte PDF
// payload is only ever base64-decoded/encoded and regex-scanned with native primitives.
// It must NEVER pass through JSON.parse/JSON.stringify — the Anthropic request body is
// assembled by string splicing around the re-encoded (therefore injection-proof) base64.

import { accountStub, isUuid } from "./account-stub.js";
import { applyNotification, creditPurchase, creditVerified } from "./purchase.js";
import { checkVoidedPurchases, creditGooglePurchase } from "./google.js";

export { Limiter, OpsAccount } from "./account.js";
export { GoogleOrders } from "./google-orders.js";

// ---- the Anthropic calls -----------------------------------------------------------------
// Model, version and prompts are DUPLICATES of core AnthropicClient.kt (the BYO-key path).
// Change them together or the metered and dev builds will extract differently.

const MODEL = "claude-haiku-4-5-20251001";
const ANTHROPIC_VERSION = "2023-06-01";
const MAX_PDF_PAGES = 100; // Anthropic's own per-request PDF page limit

const EXTRACT_PDF_SYSTEM =
  "You extract recipe ingredient lists. Return ONLY JSON. " +
  'If there is a single recipe, return {"ingredients": ["..."]}. ' +
  'If there are multiple recipes, return {"recipes": [{"name": "...", "ingredients": ["..."]}]}. ' +
  "Keep quantities/units. Do not include method steps.";
const EXTRACT_TEXT_SYSTEM =
  "You extract recipe ingredient lists. Return ONLY JSON. " +
  'Return {"ingredients": ["..."]}. ' +
  "Keep quantities/units. Do not include method steps.";
const TITLE_SYSTEM =
  'You name recipes. Return ONLY JSON: {"title": "..."} — a short, ' +
  "natural recipe name for the dish in the document. No prose.";

/** A Messages request around a PDF, spliced as strings — see the performance contract. */
function pdfRequestBody(system, maxTokens, base64, userText) {
  return (
    `{"model":${JSON.stringify(MODEL)},"max_tokens":${maxTokens},"system":${JSON.stringify(system)},` +
    `"messages":[{"role":"user","content":[{"type":"document","source":{"type":"base64",` +
    `"media_type":"application/pdf","data":"${base64}"}},{"type":"text","text":${JSON.stringify(userText)}}]},` +
    `{"role":"assistant","content":"{"}]}`
  );
}

function textRequestBody(recipeName, unitsClause, text) {
  return JSON.stringify({
    model: MODEL,
    max_tokens: 2048,
    system: EXTRACT_TEXT_SYSTEM,
    messages: [
      { role: "user", content: `Recipe name: ${recipeName}.${unitsClause}\n\nText to extract from:\n${text}` },
      { role: "assistant", content: "{" },
    ],
  });
}

/**
 * Relay to Anthropic. The reply passes through VERBATIM (the Kotlin client parses Anthropic's
 * shapes) with one exception: an upstream 401/403 means OUR key is broken, and must never
 * reach a metered user as "your API key is invalid" — it becomes a 502.
 */
async function relay(env, body) {
  const upstream = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": env.ANTHROPIC_API_KEY || "",
      "anthropic-version": ANTHROPIC_VERSION,
      "content-type": "application/json",
    },
    body,
  });
  const text = await upstream.text();
  if (upstream.status === 401 || upstream.status === 403) {
    console.log(JSON.stringify({ op: "relay", status: upstream.status, note: "upstream auth failed" }));
    return { ok: false, status: 502, text: '{"error":{"type":"api_error","message":"The conversion service is misconfigured. Please try again later."}}' };
  }
  return { ok: upstream.ok, status: upstream.status, text };
}

// ---- PDF page counting (server-authoritative billing) ------------------------------------

/**
 * Count a PDF's pages without a PDF library (a full parse would blow the free-plan CPU
 * budget): every page object carries "/Type /Page", and the negative lookahead keeps
 * "/Pages" tree nodes out. Pages hidden inside compressed object streams are missed, which
 * UNDERcharges — an accepted revenue leak, never a way to overcharge or exploit.
 */
export function countPages(bytes) {
  const text = new TextDecoder("latin1").decode(bytes);
  const matches = text.match(/\/Type\s*\/Page(?![a-zA-Z])/g);
  return Math.max(1, matches ? matches.length : 0);
}

// ---- helpers ------------------------------------------------------------------------------

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const fail = (status, error) => json(status, { error });
const passthrough = (status, text) =>
  new Response(text, { status, headers: { "content-type": "application/json" } });

function bearerToken(request) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : null;
  return isUuid(token) ? token : null;
}

/**
 * Read, validate and canonicalise a base64 PDF body. Re-encoding from the decoded bytes is
 * what makes splicing into the JSON request safe: whatever arrived, what we splice is pure
 * [A-Za-z0-9+/=]. Returns { base64, bytes } or { error: Response }.
 */
async function readPdfBody(request, env) {
  const maxBytes = Number(env.MAX_PDF_BYTES || 10 * 1024 * 1024);
  const raw = await request.text();
  if (raw.length > Math.ceil((maxBytes * 4) / 3) + 16) return { error: fail(413, "pdf_too_large") };
  let bytes;
  try {
    bytes = Buffer.from(raw, "base64");
  } catch {
    return { error: fail(422, "not_base64") };
  }
  if (bytes.length < 5 || bytes.toString("latin1", 0, 5) !== "%PDF-") return { error: fail(422, "not_a_pdf") };
  return { base64: bytes.toString("base64"), bytes };
}

function recipeName(url) {
  return (url.searchParams.get("name") || "Recipe").slice(0, 200);
}

// The user's preferred unit system, carried as ?units= by the client (Settings -> Units).
// Wording approved by Chris 2026-10-01; MeteredOcrClient.kt documents the parameter and
// AnthropicClient.kt (BYO path) carries the same clause — keep the three in step.
const UNITS_TARGETS = {
  metric: "metric units (grams, millilitres)",
  us: "US customary units (ounces, pounds, cups, fluid ounces)",
};

function unitsClause(url) {
  const target = UNITS_TARGETS[url.searchParams.get("units")];
  if (!target) return "";
  return (
    ` Convert every quantity to ${target}; use weight for dry-volume measures such as cups` +
    " and sticks, applying standard culinary densities, and round to practical shopping amounts."
  );
}

// ---- routing ------------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    let response;
    try {
      response = await route(request, env, url, parts);
    } catch (e) {
      console.log(JSON.stringify({ op: "error", message: String(e?.message || e) }));
      response = fail(500, "server_error");
    }
    // Never log tokens, names or payloads: an operation name and a status is the whole story.
    console.log(JSON.stringify({ op: `${request.method} /${parts.join("/")}`.split("?")[0], status: response.status }));
    return response;
  },

  // Rethrowing marks the cron run failed in the dashboard; the next run retries from the
  // unchanged checkpoint.
  async scheduled(controller, env) {
    try {
      console.log(JSON.stringify({ op: "voided_purchases", ...(await checkVoidedPurchases(env)) }));
    } catch (e) {
      console.log(JSON.stringify({ op: "voided_purchases", error: String(e?.message || e) }));
      throw e;
    }
  },
};

// A Play purchase token is at most a few hundred characters; anything far larger is not a
// purchase and is refused before it is parsed.
const MAX_GOOGLE_PURCHASE_BODY = 8192;

async function route(request, env, url, parts) {
  // Google purchases need the bearer: unlike Apple's JWS, nothing in the request names the
  // account, and the verified purchase must carry the same token (obfuscatedExternalAccountId).
  if (parts.length === 3 && parts[0] === "v1" && parts[1] === "purchase" && parts[2] === "google") {
    if (request.method !== "POST") return fail(404, "not_found");
    const token = bearerToken(request);
    if (!token) return fail(401, { type: "unauthorised", message: "A bearer account token is required." });
    const raw = await request.text();
    let body;
    try {
      if (raw.length > MAX_GOOGLE_PURCHASE_BODY) throw new Error("too large");
      body = JSON.parse(raw);
    } catch {
      return fail(422, { type: "bad_request", message: "The body must be a small JSON object." });
    }
    const r = await creditGooglePurchase(env, token, body);
    return json(r.status, r.body);
  }
  if (parts[0] !== "v1" || parts.length !== 2) return fail(404, "not_found");
  const endpoint = parts[1];
  const method = request.method;

  // Purchase crediting and Apple's server notifications authenticate by signature, not bearer.
  if (endpoint === "purchase" && method === "POST") {
    let body;
    try {
      body = await request.json();
    } catch {
      return fail(422, "bad_request");
    }
    // Staging-only: Xcode's local StoreKit environment signs with a local certificate no
    // server can verify, so Debug builds may submit the transaction FIELDS instead of a JWS.
    // Production never sets TEST_MODE, and the deploy script asserts this shape is refused.
    if (body.test) {
      if (env.TEST_MODE !== "1") return fail(403, "test_mode_disabled");
      const { productId, transactionId, appAccountToken } = body.test;
      const r = await creditVerified(env, { productId, transactionId, appAccountToken, quantity: 1 }, "Xcode");
      return json(r.status, r.body);
    }
    const r = await creditPurchase(env, body.jws);
    return json(r.status, r.body);
  }
  if (endpoint === "asn" && method === "POST") {
    let body;
    try {
      body = await request.json();
    } catch {
      return fail(422, "bad_request");
    }
    const r = await applyNotification(env, body.signedPayload);
    return json(r.status, r.body);
  }

  const token = bearerToken(request);
  if (!token) return fail(401, "unauthorised");
  const address = request.headers.get("cf-connecting-ip") || "unknown";
  const account = accountStub(env, token);
  const ensured = await account.ensure(address);
  if (ensured.limited) return fail(429, "slow_down");

  if (endpoint === "balance" && method === "GET") {
    // purchased lets the client mirror the naming-cap exemption rule locally.
    return json(200, { balance: ensured.balance, purchased: await account.hasPurchased() });
  }

  if (endpoint === "extract" && method === "POST") {
    const pdf = await readPdfBody(request, env);
    if (pdf.error) return pdf.error;
    const pages = countPages(pdf.bytes);
    if (pages > MAX_PDF_PAGES) return fail(422, "too_many_pages");
    const opId = crypto.randomUUID();
    const reserved = await account.reserve(opId, pages);
    if (!reserved.ok) {
      return json(402, {
        error: { type: "insufficient_ops", message: `Not enough conversion credits: need ${reserved.needed}, have ${reserved.balance}` },
        needed: reserved.needed,
        balance: reserved.balance,
      });
    }
    const reply = await relay(
      env,
      pdfRequestBody(EXTRACT_PDF_SYSTEM, 2048, pdf.base64, `Extract the ingredient list(s) for: ${recipeName(url)}.${unitsClause(url)} Return JSON only.`),
    );
    await account.settle(opId, reply.ok);
    return passthrough(reply.status, reply.text);
  }

  if (endpoint === "extract-text" && method === "POST") {
    const text = (await request.text()).slice(0, 100_000);
    if (text.trim() === "") return fail(422, "empty_text");
    const opId = crypto.randomUUID();
    const reserved = await account.reserve(opId, 1);
    if (!reserved.ok) {
      return json(402, {
        error: { type: "insufficient_ops", message: `Not enough conversion credits: need 1, have ${reserved.balance}` },
        needed: 1,
        balance: reserved.balance,
      });
    }
    const reply = await relay(env, textRequestBody(recipeName(url), unitsClause(url), text));
    await account.settle(opId, reply.ok);
    return passthrough(reply.status, reply.text);
  }

  if (endpoint === "title" && method === "POST") {
    // Paying customers skip the free naming cap while their purchased credits last —
    // their packs fund the title calls. The paid ceiling only guards a leaked token.
    const paid = ensured.balance > 0 && (await account.hasPurchased());
    const limit = Number(paid ? env.PAID_TITLE_DAILY_LIMIT || 500 : env.TITLE_DAILY_LIMIT || 30);
    if (!(await account.titleAllowed(limit))) return fail(429, "slow_down");
    const pdf = await readPdfBody(request, env);
    if (pdf.error) return pdf.error;
    const reply = await relay(env, pdfRequestBody(TITLE_SYSTEM, 100, pdf.base64, "Name this recipe. Return JSON only."));
    return passthrough(reply.status, reply.text);
  }

  return fail(404, "not_found");
}
