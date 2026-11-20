#!/bin/sh
# Runs native/smoke/agent-smoke.sh in a disposable container (the Mandrel UBI 9 image: glibc 2.34
# for the binary, a JDK for the jar), with no network but its own loopback; only the artifact and
# the scripts are mounted, read-only. Nothing of the host — its /opt, /service, ports, a real
# agent — is reachable.
#
#   native/smoke/run.sh native <dc-agent-binary>
#   native/smoke/run.sh jar    <dc-agent.jar>
#   native/smoke/run.sh trace  <dc-agent.jar> <out-dir>   # the jar under the tracing agent;
#                                                        # <out-dir> gets reachability-metadata.json
# Environment: CONTAINER (podman | docker).
set -eu
KIND=${1:?usage: run.sh native|jar|trace <artifact> [out-dir]}
ARTIFACT=${2:?usage: run.sh native|jar|trace <artifact> [out-dir]}
ARTIFACT=$(cd "$(dirname "$ARTIFACT")" && pwd)/$(basename "$ARTIFACT")   # before the cd below
if [ "$KIND" = trace ]; then
  OUT=${3:?usage: run.sh trace <dc-agent.jar> <out-dir>}
  mkdir -p "$OUT"
  OUT=$(cd "$OUT" && pwd)
fi
cd "$(dirname "$0")"
. ../image.sh
CONTAINER=${CONTAINER:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp agent-smoke.sh "$WORK/"
EXTRA=""
case "$KIND" in
  native) cp "$ARTIFACT" "$WORK/dc-agent";     ENV="-e AGENT_KIND=native" ;;
  jar)    cp "$ARTIFACT" "$WORK/dc-agent.jar"; ENV="-e AGENT_KIND=jar" ;;
  trace)  cp "$ARTIFACT" "$WORK/dc-agent.jar"
          # one output directory per JVM (the server and the CLI calls run at the same time, and an
          # agent directory takes one writer); merged into reachability-metadata.json afterwards
          ENV="-e AGENT_KIND=jar -e JAVA_AGENT_OPTS=-agentlib:native-image-agent=config-output-dir=/trace/run-{pid}-{datetime}"
          EXTRA="-v $OUT:/trace:Z" ;;
  *) echo "unknown kind $KIND" >&2; exit 2 ;;
esac

RUNTIME_OPTS=""
[ "$CONTAINER" = podman ] && RUNTIME_OPTS="--cgroup-manager=cgroupfs"
# shellcheck disable=SC2086
"$CONTAINER" run --rm $RUNTIME_OPTS --user 0 --network=none --cpus=4 --memory=4g \
  -v "$WORK":/smoke:ro,Z $EXTRA $ENV --entrypoint sh "$MANDREL_IMAGE" /smoke/agent-smoke.sh

if [ "$KIND" = trace ]; then
  # shellcheck disable=SC2016
  "$CONTAINER" run --rm $RUNTIME_OPTS --user 0 --network=none -v "$OUT":/trace:Z --entrypoint sh "$MANDREL_IMAGE" -c \
    'set -e; args=""; for d in /trace/run-*; do args="$args --input-dir=$d"; done
     native-image-configure generate $args --output-dir=/trace/merged
     cp /trace/merged/reachability-metadata.json /trace/reachability-metadata.json
     rm -rf /trace/run-* /trace/merged; chmod a+r /trace/reachability-metadata.json'
fi
