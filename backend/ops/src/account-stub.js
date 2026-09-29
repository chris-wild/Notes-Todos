// Shared by index.js and purchase.js (kept out of account.js so importing the stub helper
// never drags DurableObject class definitions into a module that only needs addressing).

export const isUuid = (value) =>
  typeof value === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

/** The one Durable Object holding [token]'s balance; the token is case-normalised. */
export const accountStub = (env, token) => env.OPS.get(env.OPS.idFromName(token.toLowerCase()));
