#!/usr/bin/env bash
# Stop everything started by up.sh (frontend, operator, agent). Runtime state under
# .run/ is kept (delete .run/ to fully reset).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

for name in frontend operator agent; do
  pf="$PID_DIR/$name.pid"
  [[ -f "$pf" ]] || continue
  pid="$(cat "$pf")"
  if kill -0 "$pid" 2>/dev/null; then
    log "Stopping $name (pid $pid)"
    pkill -P "$pid" 2>/dev/null || true   # children first (e.g. rsbuild under yarn)
    kill "$pid" 2>/dev/null || true
  fi
  rm -f "$pf"
done

log "Stopped. (Runtime state kept in $RUN_DIR — 'rm -rf verify/.run' to reset.)"
