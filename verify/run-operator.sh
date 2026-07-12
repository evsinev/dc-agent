#!/usr/bin/env bash
# Start dc-operator on :8052 in the background, pointed at the local config git repo
# and the local dc-agent fleet. Works for both modes — the difference is only whether
# the operator jar was built with the UI baked in (see build-backend.sh).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

resolve_java
[[ -f "$OPERATOR_JAR" ]] || die "operator jar not built: $OPERATOR_JAR — run: verify/build-backend.sh <mode>"
[[ -d "$CONFIG_DIR/.git" ]] || die "config repo missing — run: verify/setup-config.sh"

mkdir -p "$LOG_DIR" "$PID_DIR"

# Write the IDEA env files, then launch from the operator one (single source of truth).
"$VERIFY_DIR/write-idea-env.sh"

log "Starting dc-operator on :$OPERATOR_PORT (REPO_DIR=$CONFIG_DIR) ..."
set -a; source "$OPERATOR_ENV_FILE"; set +a
nohup "$JAVA" -jar "$OPERATOR_JAR" > "$LOG_DIR/operator.log" 2>&1 &
echo $! > "$PID_DIR/operator.pid"

# The React page servlet ('/') returns 200 without auth — good readiness probe.
wait_http "$OPERATOR_URL" "dc-operator"
