#!/usr/bin/env bash
# Build the backend jars from source (../dc-agent, JDK 21, needs maven.pne.io access).
#   separate : operator jar WITHOUT a bundled UI (frontend runs via `yarn dev`)
#   bundled  : `yarn build` here, then operator jar with the LOCAL dist baked in
#              (-Pfrontend-local) — this is how a real release consumes the tarball.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

mode="${1:-separate}"
[[ "$mode" == "separate" || "$mode" == "bundled" ]] || die "usage: build-backend.sh <separate|bundled>"
resolve_java
[[ -d "$DC_AGENT_DIR" ]] || die "dc-agent backend not found at $DC_AGENT_DIR (set DC_AGENT_DIR=...)."
[[ -x "$DC_AGENT_DIR/mvnw" ]] || die "$DC_AGENT_DIR/mvnw not found/executable."

log "Building dc-agent (agent) jar ..."
( cd "$DC_AGENT_DIR" && ./mvnw -pl dc-agent-app -am -Pagent-shaded package -DskipTests )
[[ -f "$AGENT_JAR" ]] || die "agent jar missing after build: $AGENT_JAR"

if [[ "$mode" == "bundled" ]]; then
  log "Building frontend (yarn build) ..."
  ( cd "$FRONTEND_DIR" && yarn build )
  log "Building dc-operator jar with the LOCAL frontend baked in (-Pfrontend-local) ..."
  ( cd "$DC_AGENT_DIR" && ./mvnw -pl dc-agent-operator -am -Pfrontend-local,operator-shaded \
      package -DskipTests -Dfrontend.dist.dir="$FRONTEND_DIR/dist" )
else
  log "Building dc-operator jar (no bundled UI; frontend served separately) ..."
  ( cd "$DC_AGENT_DIR" && ./mvnw -pl dc-agent-operator -am -Poperator-shaded \
      package -DskipTests -Dfrontend.skip=true )
fi
[[ -f "$OPERATOR_JAR" ]] || die "operator jar missing after build: $OPERATOR_JAR"

log "Backend jars ready:"
log "  $AGENT_JAR"
log "  $OPERATOR_JAR"
