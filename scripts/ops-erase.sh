#!/usr/bin/env bash
#
# Erase one install's server-side data, for a deletion request received through hobpad.app's
# support form (the Worker side is backend/ops/src/admin.js).
#
#   scripts/ops-erase.sh production google GPA.1234-5678-9012-34567   by Google Play order number
#   scripts/ops-erase.sh production apple  MT3BQRK2W5                 by App Store receipt order ID
#   scripts/ops-erase.sh production token  1b4e28ba-2fa1-11d2-883f-…   by account identifier
#   scripts/ops-erase.sh staging …                                     the same against staging
#
# The admin secret lives in ~/keystores/hobpad-ops-admin.env as ADMIN_TOKEN (chmod 600) and is
# set on both Workers with `npx wrangler secret put ADMIN_TOKEN --name <worker>`. It is never
# printed. The Worker answers 200 { erased, googleOrders } or 404 when nothing is held.
set -euo pipefail

TARGET="${1:-}"; KIND="${2:-}"; VALUE="${3:-}"
case "$TARGET" in
  staging) URL="https://hobpad-ops-staging.chris-f50.workers.dev" ;;
  production) URL="https://hobpad-ops.chris-f50.workers.dev" ;;
  *) echo "usage: $0 staging|production google|apple <order number> | token <uuid>" >&2; exit 1 ;;
esac
case "$KIND" in
  google) BODY="$(python3 -c 'import json,sys; print(json.dumps({"googleOrderId": sys.argv[1]}))' "$VALUE")" ;;
  apple) BODY="$(python3 -c 'import json,sys; print(json.dumps({"appleOrderId": sys.argv[1]}))' "$VALUE")" ;;
  token) BODY="$(python3 -c 'import json,sys; print(json.dumps({"token": sys.argv[1]}))' "$VALUE")" ;;
  *) echo "usage: $0 staging|production google|apple <order number> | token <uuid>" >&2; exit 1 ;;
esac
[ -n "$VALUE" ] || { echo "Give the order number or identifier to erase." >&2; exit 1; }

CRED="$HOME/keystores/hobpad-ops-admin.env"
[ -f "$CRED" ] || { echo "Missing $CRED (ADMIN_TOKEN=...)" >&2; exit 1; }
set -a; . "$CRED"; set +a
: "${ADMIN_TOKEN:?ADMIN_TOKEN is not set in $CRED}"

curl -sS -w "\nHTTP %{http_code}\n" -X POST "$URL/v1/admin/erase" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d "$BODY"
