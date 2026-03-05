#!/usr/bin/env bash
set -euo pipefail

# OWASP ZAP scanner — runs via the official Docker image.
#
# Usage:
#   bash scripts/owasp-scan.sh <target-url> [--full]
#
# Modes:
#   baseline (default)  Passive scan only — safe for production. ~2-5 min.
#   --full              Active scan — sends attack payloads. Staging/local only. ~15-30 min.
#
# Reports are written to reports/owasp/

DOCKER_BIN="${DOCKER_BIN:-docker}"
ZAP_IMAGE="ghcr.io/zaproxy/zaproxy:stable"

TARGET="${1:-}"
MODE="baseline"

if [ -z "$TARGET" ]; then
  echo "Usage: $0 <target-url> [--full]"
  echo ""
  echo "Examples:"
  echo "  $0 https://yourapp.com              # passive baseline scan (safe for prod)"
  echo "  $0 http://localhost:3001 --full      # active full scan (staging/local only)"
  exit 1
fi

shift
while [ $# -gt 0 ]; do
  case "$1" in
    --full) MODE="full"; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Ensure Docker is available
if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/reports/owasp"
mkdir -p "$REPORT_DIR"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
REPORT_HTML="$REPORT_DIR/zap-report-${MODE}-${TIMESTAMP}.html"
REPORT_JSON="$REPORT_DIR/zap-report-${MODE}-${TIMESTAMP}.json"

echo "OWASP ZAP ${MODE} scan"
echo "  Target: $TARGET"
echo "  Reports: $REPORT_DIR/"
echo ""

# ZAP config: custom rules file if present
ZAP_RULES_ARGS=""
if [ -f "$ROOT_DIR/scripts/zap-rules.tsv" ]; then
  ZAP_RULES_ARGS="-c $ROOT_DIR/scripts/zap-rules.tsv"
fi

# For local targets, use host networking so ZAP can reach localhost
NETWORK_ARGS=""
if echo "$TARGET" | grep -qE 'localhost|127\.0\.0\.1'; then
  NETWORK_ARGS="--network host"
fi

if [ "$MODE" = "baseline" ]; then
  echo "Running passive baseline scan (safe for production)..."
  "$DOCKER_BIN" run --rm \
    $NETWORK_ARGS \
    -v "$REPORT_DIR:/zap/wrk:rw" \
    "$ZAP_IMAGE" \
    zap-baseline.py \
      -t "$TARGET" \
      -J "zap-report-${MODE}-${TIMESTAMP}.json" \
      -r "zap-report-${MODE}-${TIMESTAMP}.html" \
      -l WARN \
      $ZAP_RULES_ARGS \
      || ZAP_EXIT=$?
else
  echo "Running ACTIVE full scan (sends attack payloads — staging/local only)..."
  echo "WARNING: Do NOT run this against production."
  echo ""
  "$DOCKER_BIN" run --rm \
    $NETWORK_ARGS \
    -v "$REPORT_DIR:/zap/wrk:rw" \
    "$ZAP_IMAGE" \
    zap-full-scan.py \
      -t "$TARGET" \
      -J "zap-report-${MODE}-${TIMESTAMP}.json" \
      -r "zap-report-${MODE}-${TIMESTAMP}.html" \
      -l WARN \
      $ZAP_RULES_ARGS \
      || ZAP_EXIT=$?
fi

ZAP_EXIT="${ZAP_EXIT:-0}"

echo ""
echo "Reports:"
[ -f "$REPORT_HTML" ] && echo "  HTML: $REPORT_HTML"
[ -f "$REPORT_JSON" ] && echo "  JSON: $REPORT_JSON"

# ZAP exit codes: 0 = pass, 1 = warnings only, 2 = failures, 3 = error
if [ "$ZAP_EXIT" -ge 2 ]; then
  echo ""
  echo "OWASP ZAP found HIGH/MEDIUM risk issues (exit code $ZAP_EXIT)."
  exit 1
elif [ "$ZAP_EXIT" -eq 1 ]; then
  echo ""
  echo "OWASP ZAP completed with warnings (low risk). Review the report."
  exit 0
else
  echo ""
  echo "OWASP ZAP scan passed."
  exit 0
fi
