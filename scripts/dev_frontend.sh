#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$HOME/Library/Logs/notes-todos-dev"
mkdir -p "$LOG_DIR"

export BROWSER=none
export PORT="${PORT:-3000}"

# CRA can occasionally exit if the port is taken; loop to keep it alive.
while true; do
  echo "[$(date -Iseconds)] starting frontend on :$PORT" >> "$LOG_DIR/frontend.log"
  /usr/local/bin/npm start >> "$LOG_DIR/frontend.log" 2>> "$LOG_DIR/frontend.err.log" || true
  echo "[$(date -Iseconds)] frontend exited; restarting in 2s" >> "$LOG_DIR/frontend.log"
  sleep 2
done
