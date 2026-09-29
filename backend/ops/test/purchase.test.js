// JWS conformance guard: Apple's app-store-server-library must construct and verify inside
// the WORKERS runtime (it leans on Node crypto's X509Certificate via nodejs_compat). If a
// workerd or wrangler upgrade regresses that, these tests go red before a deploy does.

import { describe, expect, it } from "vitest";
import { SELF } from "cloudflare:test";
import { Environment, SignedDataVerifier, VerificationException } from "@apple/app-store-server-library";
import { appleRootCertificates } from "../src/apple-roots.js";

const b64url = (obj) => Buffer.from(JSON.stringify(obj)).toString("base64url");
const forgedJws = (payload) => `${b64url({ alg: "ES256", x5c: [] })}.${b64url(payload)}.${"A".repeat(86)}`;

describe("SignedDataVerifier in workerd", () => {
  it("constructs against the committed Apple roots", () => {
    const roots = appleRootCertificates();
    expect(roots.length).toBe(3);
    expect(() => new SignedDataVerifier(roots, false, Environment.SANDBOX, "uk.co.promptbuilt.hobpad")).not.toThrow();
  });

  it("rejects a forged transaction with VerificationException, not a crash", async () => {
    const verifier = new SignedDataVerifier(appleRootCertificates(), false, Environment.SANDBOX, "uk.co.promptbuilt.hobpad");
    await expect(
      verifier.verifyAndDecodeTransaction(forgedJws({ environment: "Sandbox", bundleId: "uk.co.promptbuilt.hobpad" })),
    ).rejects.toBeInstanceOf(VerificationException);
  });
});

describe("purchase route hardening", () => {
  const post = (path, body) => SELF.fetch(`https://ops.test${path}`, { method: "POST", body: JSON.stringify(body) });

  it("refuses a malformed JWS", async () => {
    expect((await post("/v1/purchase", { jws: "not-a-jws" })).status).toBe(422);
  });

  it("refuses a forged JWS claiming an allowed environment", async () => {
    const reply = await post("/v1/purchase", { jws: forgedJws({ environment: "Sandbox" }) });
    expect(reply.status).toBe(403);
    expect((await reply.json()).error).toBe("verification_failed");
  });

  it("refuses environments outside the allowlist (Xcode-signed JWS can never credit)", async () => {
    expect((await post("/v1/purchase", { jws: forgedJws({ environment: "Xcode" }) })).status).toBe(403);
  });

  it("refuses a forged App Store Server Notification", async () => {
    const reply = await post("/v1/asn", { signedPayload: forgedJws({ data: { environment: "Sandbox" } }) });
    expect(reply.status).toBe(403);
  });
});
