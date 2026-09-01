#!/usr/bin/env bash
# Deploy notes-todos to the serverless stack:
#   frontend -> S3 (static)   |   backend -> Lambda container image
#   fronted by CloudFront (news/todo.promptbuilt.co.uk).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"

PROFILE=notes-todos-prod; REGION=eu-west-2; ACCT=084953646311
ECR_REPO=notes-todos-lambda; FN=notes-todos-web
STATIC_BUCKET=notes-todos-web-084953646311-eu-west-2
DIST_ID=E3MNPLNDNY4MOK
IMAGE="${ACCT}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO}:latest"

echo "1/5 Building frontend..."
CI=false npm run build

echo "2/5 Syncing static -> s3://${STATIC_BUCKET} ..."
AWS_PROFILE=$PROFILE aws s3 sync build/ "s3://${STATIC_BUCKET}/" --delete \
  --exclude "*.html" --cache-control "public, max-age=86400" --only-show-errors
AWS_PROFILE=$PROFILE aws s3 sync build/ "s3://${STATIC_BUCKET}/" \
  --exclude "*" --include "*.html" --content-type "text/html; charset=utf-8" \
  --cache-control "public, max-age=300" --only-show-errors

echo "3/5 Building + pushing arm64 image..."
AWS_PROFILE=$PROFILE aws ecr get-login-password --region $REGION \
  | docker login --username AWS --password-stdin "${ACCT}.dkr.ecr.${REGION}.amazonaws.com"
docker buildx build --platform linux/arm64 --provenance=false --sbom=false -t "$IMAGE" --push .

echo "4/5 Updating Lambda..."
AWS_PROFILE=$PROFILE aws lambda update-function-code --region $REGION \
  --function-name $FN --image-uri "$IMAGE" >/dev/null
AWS_PROFILE=$PROFILE aws lambda wait function-updated --region $REGION --function-name $FN

echo "5/5 Invalidating CloudFront..."
AWS_PROFILE=$PROFILE aws cloudfront create-invalidation --distribution-id $DIST_ID \
  --paths "/*" --query 'Invalidation.Status' --output text

echo "Done. https://news.promptbuilt.co.uk"
