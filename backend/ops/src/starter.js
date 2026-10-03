// The free starter credits, granted once per physical device rather than once per account.
// The account identifier lives on the device and can be lost (Clear storage on Android, a
// deletion request), so the record of "this device has had its free credits" is kept by the
// platform instead, in a per-device bit that survives reinstalls and factory resets:
//
//   iOS      Apple DeviceCheck, bit0.               Secret DEVICECHECK_PRIVATE_KEY (.p8 PEM),
//                                                   vars DEVICECHECK_KEY_ID and APPLE_TEAM_ID.
//   Android  Play Integrity device recall, bitFirst. The service account in
//                                                   GOOGLE_SERVICE_ACCOUNT_JSON (src/google.js).
//
// BIT RESERVATION: both platforms share these bits across every app in the developer account,
// so RiderNav (same Apple team, same Play developer account) must never write DeviceCheck bit0
// or device recall bitFirst. They mean "this device has had HobPad's free starter credits".
//
// The client asks for the check while its account's starter state is "pending" (GET
// /v1/balance reports it). The bit is set BEFORE the credits are added: a failure in between
// costs one device its free credits, but two racing checks can never grant twice, because the
// account settles its starter state exactly once (OpsAccount.resolveStarter).
//
// Anything short of a clear answer (platform outage, device recall not available yet, a
// device that fails integrity) leaves the state "pending" so a later launch can try again;
// only "this device already had them" settles it as "denied".

import { accessToken, GoogleError } from "./google.js";

const DEVICECHECK = {
  production: "https://api.devicecheck.apple.com/v1",
  development: "https://api.development.devicecheck.apple.com/v1",
};
const PLAY_INTEGRITY = "https://playintegrity.googleapis.com/v1";
// An integrity token older than this is refused; the client requests one immediately before.
const MAX_TOKEN_AGE_MS = 15 * 60 * 1000;
const MAX_DEVICE_TOKEN = 4096;

export class StarterError extends Error {
  constructor(status, type, message) {
    super(message);
    this.status = status;
    this.type = type;
  }
}

const unavailable = (message) => new StarterError(503, "check_unavailable", message);
const b64url = (data) => Buffer.from(typeof data === "string" ? data : new Uint8Array(data)).toString("base64url");

/**
 * The Play Integrity nonce that binds a device check to one account: the client computes the
 * same value, so a token obtained for one account is useless for another.
 */
export async function starterNonce(token) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`hobpad-starter:${token.toLowerCase()}`));
  return b64url(digest);
}

// ---- Apple DeviceCheck ----------------------------------------------------------------------

let cachedJwt = null; // { keyId, jwt, expiresAt }

/** Tests mint a fresh key per run; production never needs to. */
export function resetDeviceCheckCache() {
  cachedJwt = null;
}

async function deviceCheckJwt(env) {
  if (!env.DEVICECHECK_PRIVATE_KEY || !env.DEVICECHECK_KEY_ID || !env.APPLE_TEAM_ID) {
    throw unavailable("Device checks are not configured.");
  }
  const now = Date.now();
  if (cachedJwt?.keyId === env.DEVICECHECK_KEY_ID && cachedJwt.expiresAt > now) return cachedJwt.jwt;
  let key;
  try {
    const der = Buffer.from(env.DEVICECHECK_PRIVATE_KEY.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64");
    key = await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  } catch {
    throw unavailable("Device checks are not configured.");
  }
  const signingInput =
    b64url(JSON.stringify({ alg: "ES256", kid: env.DEVICECHECK_KEY_ID })) +
    "." +
    b64url(JSON.stringify({ iss: env.APPLE_TEAM_ID, iat: Math.floor(now / 1000) }));
  // WebCrypto's ECDSA signature is already the raw r||s form JWS expects.
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(signingInput));
  // Apple accepts a token for an hour; renewing well inside that keeps clock skew harmless.
  cachedJwt = { keyId: env.DEVICECHECK_KEY_ID, jwt: `${signingInput}.${b64url(signature)}`, expiresAt: now + 20 * 60 * 1000 };
  return cachedJwt.jwt;
}

async function deviceCheckCall(env, base, endpoint, body) {
  const jwt = await deviceCheckJwt(env);
  let reply;
  try {
    reply = await fetch(`${base}/${endpoint}`, {
      method: "POST",
      headers: { authorization: `Bearer ${jwt}`, "content-type": "application/json" },
      body: JSON.stringify({ ...body, transaction_id: crypto.randomUUID(), timestamp: Date.now() }),
    });
  } catch {
    throw unavailable("Apple could not be reached.");
  }
  const text = await reply.text();
  if (reply.status === 400 && /device token/i.test(text)) {
    throw new StarterError(422, "bad_device_token", "The device token was not accepted.");
  }
  if (reply.status === 401 || reply.status === 403 || reply.status === 400) {
    cachedJwt = null;
    console.log(JSON.stringify({ op: "devicecheck", endpoint, status: reply.status, note: text.slice(0, 60) }));
    throw unavailable("Device checks are misconfigured.");
  }
  if (!reply.ok) throw unavailable("Apple could not be reached.");
  return text;
}

const appleCheck = {
  async inspect(env, token, body) {
    const deviceToken = body.deviceToken;
    if (typeof deviceToken !== "string" || !deviceToken || deviceToken.length > MAX_DEVICE_TOKEN || /\s/.test(deviceToken)) {
      throw new StarterError(422, "bad_device_token", "A DeviceCheck token is required.");
    }
    // Development-signed builds get development tokens, which only staging may accept.
    const environment = body.environment === "development" && env.TEST_MODE === "1" ? "development" : "production";
    const base = DEVICECHECK[environment];
    const text = await deviceCheckCall(env, base, "query_two_bits", { device_token: deviceToken });
    let used = false;
    if (!/bit state not found/i.test(text)) {
      try {
        used = JSON.parse(text).bit0 === true;
      } catch {
        throw unavailable("Apple's answer could not be read.");
      }
    }
    return {
      used,
      mark: () => deviceCheckCall(env, base, "update_two_bits", { device_token: deviceToken, bit0: true }),
    };
  },
};

// ---- Google Play Integrity with device recall ---------------------------------------------

async function playIntegrityCall(env, path, body) {
  let auth;
  try {
    auth = await accessToken(env);
  } catch (e) {
    if (e instanceof GoogleError) throw unavailable(e.message);
    throw e;
  }
  let reply;
  try {
    reply = await fetch(`${PLAY_INTEGRITY}/${path}`, {
      method: "POST",
      headers: { authorization: `Bearer ${auth}`, "content-type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw unavailable("Google could not be reached.");
  }
  if (reply.status === 400) throw new StarterError(422, "bad_integrity_token", "The integrity token was not accepted.");
  if (reply.status === 401 || reply.status === 403) {
    console.log(JSON.stringify({ op: "play_integrity", path: path.split(":").pop(), status: reply.status }));
    throw unavailable("Device checks are misconfigured.");
  }
  if (!reply.ok) throw unavailable("Google could not be reached.");
  return reply.json().catch(() => {
    throw unavailable("Google's answer could not be read.");
  });
}

const googleCheck = {
  async inspect(env, token, body) {
    const integrityToken = body.integrityToken;
    if (typeof integrityToken !== "string" || !integrityToken || integrityToken.length > 16384) {
      throw new StarterError(422, "bad_integrity_token", "An integrity token is required.");
    }
    const pkg = env.PLAY_PACKAGE_NAME;
    const decoded = await playIntegrityCall(env, `${pkg}:decodeIntegrityToken`, { integrity_token: integrityToken });
    const payload = decoded?.tokenPayloadExternal ?? {};
    const request = payload.requestDetails ?? {};
    const strip = (value) => String(value ?? "").replace(/=+$/, "");
    if (request.requestPackageName !== pkg || strip(request.nonce) !== (await starterNonce(token))) {
      throw new StarterError(422, "bad_integrity_token", "The integrity token was not made for this account.");
    }
    if (!(Math.abs(Date.now() - Number(request.timestampMillis)) <= MAX_TOKEN_AGE_MS)) {
      throw new StarterError(422, "bad_integrity_token", "The integrity token is too old.");
    }
    const appOk = payload.appIntegrity?.appRecognitionVerdict === "PLAY_RECOGNIZED";
    const deviceOk = (payload.deviceIntegrity?.deviceRecognitionVerdict ?? []).includes("MEETS_DEVICE_INTEGRITY");
    if (!appOk || !deviceOk) {
      // Not settled as denied: a device can pass later (for example after a Play services update).
      throw new StarterError(403, "untrusted_device", "This copy of HobPad or this device could not be verified.");
    }
    // Google documents empty values only for "unavailable". Until a never-written bit has been
    // observed on a real device, anything without an explicit bitFirst waits rather than grants.
    const values = payload.deviceIntegrity?.deviceRecall?.values ?? {};
    if (typeof values.bitFirst !== "boolean") throw unavailable("Device recall is not available yet.");
    return {
      used: values.bitFirst,
      mark: () => playIntegrityCall(env, `${pkg}/deviceRecall:write`, { integrityToken, newValues: { bitFirst: true } }),
    };
  },
};

// ---- the staging-only check for simulators and emulators -----------------------------------

const testCheck = {
  async inspect(env, token, body) {
    if (env.TEST_MODE !== "1") throw new StarterError(403, "test_mode_disabled", "Test device checks are disabled.");
    return { used: body.used === true, mark: async () => {} };
  },
};

const CHECKS = { ios: appleCheck, android: googleCheck, test: testCheck };

/**
 * POST /v1/starter. Returns { status, body } where body is { starter, balance } on success.
 */
export async function claimStarter(env, token, account, body) {
  const current = await account.starter();
  if (current.starter !== "pending") return { status: 200, body: current };
  const check = CHECKS[body?.platform];
  if (!check) return { status: 422, body: { error: { type: "bad_request", message: "Unknown platform." } } };
  try {
    const { used, mark } = await check.inspect(env, token, body);
    if (!used) await mark();
    const result = await account.resolveStarter(!used);
    console.log(JSON.stringify({ op: "starter", platform: body.platform, starter: result.starter }));
    return { status: 200, body: result };
  } catch (e) {
    if (!(e instanceof StarterError)) throw e;
    console.log(JSON.stringify({ op: "starter", platform: body.platform, error: e.type }));
    return { status: e.status, body: { error: { type: e.type, message: e.message }, starter: "pending" } };
  }
}
