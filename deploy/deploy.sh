#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Semgrep security scan - fail if issues found
echo "🔍 Running Semgrep security scan..."
semgrep --config=p/javascript --config=p/react --config=p/secrets --exclude='backend/db.js' --error . || {
  echo ""
  echo "❌ Semgrep found issues. Please fix them before deploying."
  echo "   Run: npm run semgrep:fix to see fixes (may require review)"
  exit 1
}

echo "✅ Semgrep passed."

if [ ! -f deploy/aws.env ]; then
  echo "Missing deploy/aws.env. Create it first." >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a; source deploy/aws.env; set +a

: "${AWS_REGION:?}"
: "${AWS_ACCOUNT_ID:?}"
: "${ECR_REPO:?}"
: "${ECS_CLUSTER:?}"
: "${ECS_SERVICE:?}"
: "${TASK_FAMILY:?}"

REPO_URI="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO"

echo "Logging into ECR..."
aws ecr get-login-password --region "$AWS_REGION" \
| docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com" >/dev/null

# Parse flags
TAG="latest"
RUN_OWASP_SCAN=false
for arg in "$@"; do
  case "$arg" in
    --scan) RUN_OWASP_SCAN=true ;;
    *) TAG="$arg" ;;
  esac
done

echo "Building image ($REPO_URI:$TAG)..."
docker build --platform linux/amd64 -t "$ECR_REPO:$TAG" .

echo "Tagging + pushing..."
docker tag "$ECR_REPO:$TAG" "$REPO_URI:$TAG"
docker push "$REPO_URI:$TAG"

# Build a new task definition revision by taking the current one and updating image.
# Also sets CORS_ORIGIN to the domain (lock down CORS) if DOMAIN is set.
TMP_JSON="/tmp/${TASK_FAMILY}-taskdef.json"
export TMP_JSON REPO_URI TAG DOMAIN

echo "Fetching current task definition..."
CURRENT_TD_ARN=$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" --region "$AWS_REGION" --query 'services[0].taskDefinition' --output text)
aws ecs describe-task-definition --task-definition "$CURRENT_TD_ARN" --region "$AWS_REGION" \
  --query 'taskDefinition' --output json > "$TMP_JSON"

# Anthropic API key secret (required for ingredient extraction in prod)
ANTHROPIC_SECRET_ID="notes-todos/ANTHROPIC_API_KEY"
ANTHROPIC_SECRET_ARN=$(aws secretsmanager describe-secret --secret-id "$ANTHROPIC_SECRET_ID" --region "$AWS_REGION" --query 'ARN' --output text 2>/dev/null || true)
export ANTHROPIC_SECRET_ARN

node - <<'NODE'
const fs = require('fs');
const p = process.env.TMP_JSON;
const td = JSON.parse(fs.readFileSync(p, 'utf8'));

// Strip read-only fields
for (const k of [
  'taskDefinitionArn','revision','status','requiresAttributes','compatibilities',
  'registeredAt','registeredBy'
]) delete td[k];

const repoUri = process.env.REPO_URI;
const tag = process.env.TAG;
const domain = process.env.DOMAIN;

const c = td.containerDefinitions.find(x => x.name === 'notes-todos-app') || td.containerDefinitions[0];
c.image = `${repoUri}:${tag}`;

// Update/insert CORS_ORIGIN
if (domain) {
  const origin = `https://${domain}`;
  const env = c.environment || (c.environment = []);
  const idx = env.findIndex(e => e.name === 'CORS_ORIGIN');
  if (idx >= 0) env[idx].value = origin;
  else env.push({ name: 'CORS_ORIGIN', value: origin });
}

// Remove legacy OPENAI_API_KEY if present
const secrets = c.secrets || (c.secrets = []);
const openaiIdx = secrets.findIndex(s => s.name === 'OPENAI_API_KEY');
if (openaiIdx >= 0) secrets.splice(openaiIdx, 1);

// Update/insert ANTHROPIC_API_KEY secret
const anthropicArn = (process.env.ANTHROPIC_SECRET_ARN || '').trim();
if (anthropicArn) {
  const idx = secrets.findIndex(s => s.name === 'ANTHROPIC_API_KEY');
  if (idx >= 0) secrets[idx].valueFrom = anthropicArn;
  else secrets.push({ name: 'ANTHROPIC_API_KEY', valueFrom: anthropicArn });
} else {
  console.warn('WARN: ANTHROPIC_SECRET_ARN is not set; ANTHROPIC_API_KEY will not be available in the task.');
}

fs.writeFileSync(p, JSON.stringify(td, null, 2));
NODE

echo "Registering new task definition revision..."
NEW_TD_ARN=$(aws ecs register-task-definition --cli-input-json "file://$TMP_JSON" --region "$AWS_REGION" --query 'taskDefinition.taskDefinitionArn' --output text)

echo "Updating ECS service to $NEW_TD_ARN ..."
aws ecs update-service --cluster "$ECS_CLUSTER" --service "$ECS_SERVICE" --task-definition "$NEW_TD_ARN" --force-new-deployment --region "$AWS_REGION" >/dev/null

echo "Waiting for service to become stable..."
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" --region "$AWS_REGION"

echo "Deploy complete."

# Optional: OWASP ZAP baseline scan against prod
if [ "$RUN_OWASP_SCAN" = true ]; then
  if [ -n "${DOMAIN:-}" ]; then
    echo ""
    echo "🔍 Running OWASP ZAP baseline scan against https://$DOMAIN ..."
    bash "$ROOT_DIR/scripts/owasp-scan.sh" "https://$DOMAIN" || {
      echo "⚠️  OWASP scan found issues. Review the report in reports/owasp/"
    }
  else
    echo ""
    echo "⚠️  --scan requested but DOMAIN is not set in deploy/aws.env. Skipping OWASP scan."
  fi
fi
