#!/usr/bin/env bash
# Start the frontend dev server (yarn dev) in the background — separate mode only.
# Serves under /dc-operator and proxies /dc-operator/api -> localhost:8052 (the operator).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

mkdir -p "$LOG_DIR" "$PID_DIR"

log "Starting frontend dev server (yarn dev) on :$FRONTEND_PORT ..."
( cd "$FRONTEND_DIR" && nohup yarn dev > "$LOG_DIR/frontend.log" 2>&1 & echo $! > "$PID_DIR/frontend.pid" )

wait_http "$FRONTEND_URL" "frontend dev server"
