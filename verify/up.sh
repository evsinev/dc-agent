#!/usr/bin/env bash
# Bring up the full local verification stack in one of two modes:
#   verify/up.sh separate [--build]   frontend via `yarn dev`, operator serves API only
#   verify/up.sh bundled   [--build]   frontend baked into dc-operator (release-style)
# --build forces a backend (and, for bundled, frontend) rebuild; otherwise existing
# jars are reused. Stop with verify/down.sh.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

mode="${1:-}"
[[ "$mode" == "separate" || "$mode" == "bundled" ]] || die "usage: verify/up.sh <separate|bundled> [--build]"
build=false
[[ "${2:-}" == "--build" ]] && build=true

resolve_java

if $build || [[ ! -f "$AGENT_JAR" || ! -f "$OPERATOR_JAR" ]]; then
  "$VERIFY_DIR/build-backend.sh" "$mode"
elif [[ "$mode" == "bundled" ]]; then
  log "Reusing existing operator jar. Pass --build to re-bake the UI after frontend changes."
fi

"$VERIFY_DIR/setup-config.sh"
"$VERIFY_DIR/run-agent.sh"
"$VERIFY_DIR/run-operator.sh"

if [[ "$mode" == "separate" ]]; then
  "$VERIFY_DIR/run-frontend.sh"
  open_url="$FRONTEND_URL"
else
  open_url="$OPERATOR_URL"
fi

cat <<EOF

──────────────────────────────────────────────────────────────────────────────
 Stack is UP in '$mode' mode.

   Open in a browser : $open_url
   Operator API      : $OPERATOR_BASE/api/*   (POST)
   Local agent       : $AGENT_BASE/health
   Logs              : $LOG_DIR/{agent,operator,frontend}.log
   IDEA env files    : $AGENT_ENV_FILE
                       $OPERATOR_ENV_FILE  (EnvFile plugin — see README)
   Demo git pull     : verify/add-commit.sh   then click "Pull from Git"
   Manual checklist   : verify/README.md
   Stop everything   : verify/down.sh
──────────────────────────────────────────────────────────────────────────────
EOF
