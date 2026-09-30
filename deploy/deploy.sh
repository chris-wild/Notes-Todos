#!/usr/bin/env bash
# Routine content deploy for hobpad.app: sync site/ to S3 and invalidate CloudFront.
# Infra (bucket, cert, OAC, distribution, DNS) was provisioned 2026-09-30, mirroring
# ridernav-app/deploy/bootstrap.sh; the created ids live in deploy/aws.env.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ ! -f deploy/aws.env ]; then
  echo "Missing deploy/aws.env" >&2
  exit 1
fi
# shellcheck disable=SC1091
set -a; source deploy/aws.env; set +a

: "${S3_BUCKET:?}"
: "${CLOUDFRONT_DIST_ID:?}"

echo "Syncing site/ -> s3://$S3_BUCKET ..."
# Assets are cached for a day (the stylesheet is VERSIONED — hobpad.v1.css — so bump the
# filename on change); HTML is cached for five minutes.
aws s3 sync site/ "s3://$S3_BUCKET/" \
  --delete \
  --exclude "*.html" \
  --cache-control "public, max-age=86400"
aws s3 sync site/ "s3://$S3_BUCKET/" \
  --exclude "*" --include "*.html" \
  --content-type "text/html; charset=utf-8" \
  --cache-control "public, max-age=300"

echo "Invalidating CloudFront cache..."
aws cloudfront create-invalidation \
  --distribution-id "$CLOUDFRONT_DIST_ID" \
  --paths "/*" \
  --query 'Invalidation.{Id:Id,Status:Status}' --output json

echo "Deploy complete. https://$DOMAIN"
