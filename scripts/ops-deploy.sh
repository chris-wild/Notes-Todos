#!/usr/bin/env bash
#
# Deploy the recipe-conversion metering proxy (backend/ops) to Cloudflare.
#
#   scripts/ops-deploy.sh staging      -> https://hobpad-ops-staging.<subdomain>.workers.dev
#   scripts/ops-deploy.sh production   -> https://hobpad-ops.<subdomain>.workers.dev
#
# RiderNav's worker-deploy.sh convention: the Cloudflare token lives in ~/keystores/cloudflare.env
# (chmod 600) as CLOUDFLARE_API_TOKEN and is never printed. Secrets each deployed name needs
# (npx wrangler secret put <NAME> --name <worker>): ANTHROPIC_API_KEY, APP_APPLE_ID once the
# App Store Connect app record exists, and GOOGLE_SERVICE_ACCOUNT_JSON (Play Developer API
# service-account key; without it Android purchases answer 503 and the refund cron no-ops).
# The STAGING worker may additionally set the plain var
# TEST_MODE=1 (wrangler deploy --name hobpad-ops-staging --var TEST_MODE:1); production must not,
# and this script proves the production copy refuses the test purchase shape after every deploy.
#
# It runs the Worker's own tests first and refuses to deploy a red build, then checks the deployed
# copy answers.
set -euo pipefail

TARGET="${1:-}"
case "$TARGET" in
  staging) NAME="hobpad-ops-staging"; EXTRA=(--var TEST_MODE:1) ;;
  production) NAME="hobpad-ops"; EXTRA=() ;;
  *) echo "usage: $0 staging|production" >&2; exit 1 ;;
esac

CRED="$HOME/keystores/cloudflare.env"
[ -f "$CRED" ] || { echo "Missing $CRED (CLOUDFLARE_API_TOKEN=...)" >&2; exit 1; }
set -a; . "$CRED"; set +a
: "${CLOUDFLARE_API_TOKEN:?CLOUDFLARE_API_TOKEN is not set in $CRED}"

api() { curl -sS -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" "https://api.cloudflare.com/client/v4$1"; }
export CLOUDFLARE_ACCOUNT_ID="$(api /accounts | python3 -c 'import json,sys; r=json.load(sys.stdin).get("result") or []; print(r[0]["id"] if r else "")')"
[ -n "$CLOUDFLARE_ACCOUNT_ID" ] || { echo "Cloudflare refused the token (no account visible)" >&2; exit 1; }
SUBDOMAIN="$(api "/accounts/$CLOUDFLARE_ACCOUNT_ID/workers/subdomain" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("result") or {}).get("subdomain",""))')"
[ -n "$SUBDOMAIN" ] || { echo "The account has no workers.dev subdomain yet (open Workers & Pages once in the dashboard)" >&2; exit 1; }

export WRANGLER_SEND_METRICS=false

cd "$(dirname "$0")/../backend/ops"
[ -d node_modules ] || npm ci
echo "==> Testing the Worker"
npx vitest run
echo "==> Deploying $NAME"
npx wrangler deploy --name "$NAME" ${EXTRA[@]+"${EXTRA[@]}"}
URL="https://$NAME.$SUBDOMAIN.workers.dev"
echo "==> Checking $URL"
# A first deploy can answer 404 from the edge for a short while before the route propagates.
CODE=""
for _ in $(seq 1 10); do
  CODE="$(curl -sS -o /dev/null -w '%{http_code}' --retry 5 --retry-delay 3 --retry-all-errors "$URL/v1/balance")"
  [ "$CODE" = "401" ] && break
  sleep 5
done
[ "$CODE" = "401" ] || { echo "Expected 401 from unauthenticated GET /v1/balance, got $CODE" >&2; exit 1; }
if [ "$TARGET" = "production" ]; then
  # The staging-only purchase shape must be dead on production: a dev build pointed at the
  # wrong URL, or a copied request, must never credit ops without a Apple-verified JWS.
  CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' \
    -d '{"test":{"productId":"uk.co.promptbuilt.hobpad.ops50","transactionId":"probe","appAccountToken":"00000000-0000-0000-0000-000000000000"}}' \
    "$URL/v1/purchase")"
  [ "$CODE" = "403" ] || { echo "Production accepted the TEST_MODE purchase shape (got $CODE) — refusing to leave it live" >&2; exit 1; }
fi
echo "==> Live: $URL"
