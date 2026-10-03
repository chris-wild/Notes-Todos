// ES256 JSON web tokens for Apple's server APIs (DeviceCheck, App Store Server API), signed with
// a .p8 key held as a Worker secret. WebCrypto's ECDSA signature is already the raw r||s form
// JWS expects, so no DER conversion is needed.

const b64url = (data) => Buffer.from(typeof data === "string" ? data : new Uint8Array(data)).toString("base64url");
const keys = new Map(); // per-isolate cache: PEM -> CryptoKey

async function signingKey(pem) {
  if (keys.has(pem)) return keys.get(pem);
  const der = Buffer.from(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64");
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  keys.set(pem, key);
  return key;
}

/** A signed token for [header] and [claims]; throws if [pem] is not a P-256 private key. */
export async function es256Jwt(pem, header, claims) {
  const signingInput = `${b64url(JSON.stringify({ alg: "ES256", ...header }))}.${b64url(JSON.stringify(claims))}`;
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, await signingKey(pem), new TextEncoder().encode(signingInput));
  return `${signingInput}.${b64url(signature)}`;
}
