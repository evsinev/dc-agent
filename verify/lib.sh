#!/usr/bin/env bash
# Shared configuration + helpers for the local verification harness.
# Source this from the other verify/*.sh scripts: `source "$(dirname "$0")/lib.sh"`.
#
# Everything here is overridable via environment variables. Tokens are local,
# NON-PRODUCTION test values — never the live qa*.clubber.me credentials.
set -euo pipefail

# ── Paths ────────────────────────────────────────────────────────────────────
VERIFY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DC_AGENT_DIR="$(cd "$VERIFY_DIR/.." && pwd)"                       # backend repo (verify/ lives here)
FRONTEND_DIR="${FRONTEND_DIR:-$(cd "$DC_AGENT_DIR/.." && pwd)/dc-agent-react}"  # sibling frontend checkout
RUN_DIR="$VERIFY_DIR/.run"                                        # gitignored runtime state
CONFIG_TEMPLATE_DIR="$VERIFY_DIR/config-template"                # operator apps/ + tasks/ template
AGENT_CONFIG_TEMPLATE_DIR="$VERIFY_DIR/agent-config"             # agent command-config examples
CONFIG_DIR="$RUN_DIR/config"                                      # operator REPO_DIR (a git checkout)
CONFIG_REMOTE="$RUN_DIR/config-remote.git"                        # bare 'origin' so /git/pull works offline
AGENT_CONFIG_DIR="$RUN_DIR/agent-config"                          # agent CONFIG_DIR (commands + api-keys)
AGENT_SERVICES_DIR="$RUN_DIR/agent-services/service"             # agent SERVICES_DIR (daemontools svc dirs)
AGENT_SERVICES_DEF_DIR="$RUN_DIR/agent-services/service.d"       # agent SERVICES_DEFINITION_DIR
AGENT_SERVICES_LOG_DIR="$RUN_DIR/agent-services/log"             # agent SERVICES_LOG_DIR
AGENT_TMP_DIR="$RUN_DIR/agent-tmp"                               # agent TEMP_DIR (docker unzip)
AGENT_ENV_FILE="$RUN_DIR/dc-agent.env"                          # IDEA EnvFile for the agent
OPERATOR_ENV_FILE="$RUN_DIR/dc-operator.env"                    # IDEA EnvFile for the operator
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"

# ── Ports / URLs ─────────────────────────────────────────────────────────────
AGENT_PORT="${AGENT_PORT:-8051}"
OPERATOR_PORT="${OPERATOR_PORT:-8052}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
AGENT_BASE="http://localhost:${AGENT_PORT}/dc-agent"
OPERATOR_BASE="http://localhost:${OPERATOR_PORT}/dc-operator"
OPERATOR_URL="http://localhost:${OPERATOR_PORT}/dc-operator/"     # bundled-mode UI
FRONTEND_URL="http://localhost:${FRONTEND_PORT}/dc-operator/"     # separate-mode UI (yarn dev)

# ── Local test tokens (agent side and operator side MUST match) ──────────────
AGENT_API_KEY="${AGENT_API_KEY:-local-agent-key}"          # -> api-key header on /docker/*
CP_TOKEN="${CP_TOKEN:-local-cp-token}"                     # -> Bearer on /control-plane/api/*
APP_STATUS_TOKEN="${APP_STATUS_TOKEN:-local-app-status-token}"  # -> Bearer on /app-status

# ── Built jars ───────────────────────────────────────────────────────────────
AGENT_JAR="$DC_AGENT_DIR/dc-agent-app/target/dc-agent.jar"
OPERATOR_JAR="$DC_AGENT_DIR/dc-agent-operator/target/dc-operator.jar"

# ── Helpers ──────────────────────────────────────────────────────────────────
log() { printf '\033[36m[verify]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[verify] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Resolve a JDK 21 into JAVA_HOME + $JAVA (mvnw and the jars need 21). Tries, in order:
# $JAVA_HOME_21, macOS java_home, SDKMAN (current + any 21* candidate), then the PATH java.
resolve_java() {
  local candidates=() c jb ver
  [[ -n "${JAVA_HOME_21:-}" ]] && candidates+=("$JAVA_HOME_21")
  if [[ -x /usr/libexec/java_home ]]; then
    c="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"; [[ -n "$c" ]] && candidates+=("$c")
  fi
  if [[ -d "$HOME/.sdkman/candidates/java" ]]; then
    [[ -x "$HOME/.sdkman/candidates/java/current/bin/java" ]] && candidates+=("$HOME/.sdkman/candidates/java/current")
    for c in "$HOME"/.sdkman/candidates/java/21*; do [[ -x "$c/bin/java" ]] && candidates+=("$c"); done
  fi
  candidates+=("")   # last resort: whatever `java` is on PATH

  for c in "${candidates[@]}"; do
    jb="${c:+$c/bin/}java"
    { [[ -x "$jb" ]] || command -v "$jb" >/dev/null 2>&1; } || continue
    ver="$("$jb" -version 2>&1 | head -1 || true)"
    if [[ "$ver" == *'"21'* ]]; then
      [[ -n "$c" ]] && export JAVA_HOME="$c"
      export JAVA="$jb"
      log "JDK: $ver"
      return 0
    fi
  done

  die "JDK 21 is required to build/run the backend, but none was found.
    Install it (SDKMAN 'sdk install java 21...' or 'brew install openjdk@21'), or point the
    harness at one:  JAVA_HOME_21=/path/to/jdk-21  verify/up.sh ..."
}

# Poll an HTTP URL until it answers 2xx (or fail after N tries).
wait_http() { # <url> <name> [tries]
  local url="$1" name="$2" tries="${3:-90}" i
  log "Waiting for $name at $url ..."
  for ((i = 0; i < tries; i++)); do
    if curl -sf -o /dev/null "$url"; then
      log "$name is up."
      return 0
    fi
    sleep 1
  done
  die "$name did not become ready at $url after ${tries}s (check $LOG_DIR)."
}
