#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# ---- Config ----
export DATABASE_URL="${DATABASE_URL:-postgres://postgres:postgres@localhost:5432/notes_todos}"
export PGSSL="${PGSSL:-false}"
export JWT_SECRET="${JWT_SECRET:-dev-secret-change-me}"
export PORT="${PORT:-3001}"

LOG_DIR="$HOME/Library/Logs/notes-todos-dev"
mkdir -p "$LOG_DIR"

# ---- Ensure local Postgres (Docker) is running ----
# If Docker Desktop isn't running yet, we'll just keep retrying.
DOCKER_BIN="${DOCKER_BIN:-/usr/local/bin/docker}"

ensure_postgres() {
  if [ ! -x "$DOCKER_BIN" ]; then
    echo "docker not found at $DOCKER_BIN" >&2
    return 1
  fi

  if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
    echo "Docker not ready yet" >&2
    return 1
  fi

  if "$DOCKER_BIN" ps --format '{{.Names}}' | grep -qx 'notes-todos-postgres'; then
    return 0
  fi

  if "$DOCKER_BIN" ps -a --format '{{.Names}}' | grep -qx 'notes-todos-postgres'; then
    "$DOCKER_BIN" start notes-todos-postgres >/dev/null
  else
    "$DOCKER_BIN" run -d --name notes-todos-postgres \
      -e POSTGRES_PASSWORD=postgres \
      -e POSTGRES_DB=notes_todos \
      -p 5432:5432 \
      postgres:17-alpine >/dev/null
  fi
}

wait_for_pg() {
  # Wait until Postgres accepts connections
  for _ in $(seq 1 60); do
    if "$DOCKER_BIN" exec notes-todos-postgres pg_isready -U postgres -d notes_todos >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Postgres did not become ready in time" >&2
  return 1
}

# ---- Main loop (in case the node process exits) ----
while true; do
  echo "[$(date -Iseconds)] ensuring docker postgres..." >> "$LOG_DIR/backend.log"
  if ensure_postgres; then
    wait_for_pg || true
  else
    sleep 2
    continue
  fi

  echo "[$(date -Iseconds)] starting backend on :$PORT" >> "$LOG_DIR/backend.log"
  cd "$ROOT_DIR/backend"
  # In dev, auto-restart on backend file changes.
  # Node 22+ supports --watch; it restarts the process when any imported file changes.
  /usr/local/bin/node --watch server.js >> "$LOG_DIR/backend.log" 2>> "$LOG_DIR/backend.err.log" || true

  echo "[$(date -Iseconds)] backend exited; restarting in 2s" >> "$LOG_DIR/backend.log"
  sleep 2
done
