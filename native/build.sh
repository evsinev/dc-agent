#!/bin/sh
# Native build of the agent: native/build.sh <dc-agent.jar> <output-binary>
#
# Runs native-image in the Mandrel builder image (UBI 9, glibc 2.34 — the binary runs on EL9 and
# newer, x86_64), pinned by digest. The build arguments live in the jar
# (META-INF/native-image/com.payneteasy/dc-agent-app/native-image.properties), so this script only
# picks the container runtime and the resources.
#
# Environment: CONTAINER (podman | docker, default: podman if present), NATIVE_CPUS (8),
# NATIVE_MEMORY (8g).
set -eu

. "$(dirname "$0")/image.sh"
IMAGE=$MANDREL_IMAGE

JAR=${1:?usage: native/build.sh <dc-agent.jar> <output-binary>}
OUT=${2:?usage: native/build.sh <dc-agent.jar> <output-binary>}
CONTAINER=${CONTAINER:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}
CPUS=${NATIVE_CPUS:-8}
MEMORY=${NATIVE_MEMORY:-8g}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# mktemp -d is 0700: nobody else can swap the jar or the binary in it; the container runs as the
# caller (podman keep-id → its uid 1001, docker --user), so no wider mode is needed
cp "$JAR" "$WORK/dc-agent.jar"

# The image runs as uid 1001; map the caller to it (rootless podman) or run as the caller (docker),
# so the binary in the bind mount is written and owned by the caller.
# :Z relabels the mount for SELinux (podman on EL); docker on the release runner does not need it.
if [ "$CONTAINER" = podman ]; then
  USER_OPTS="--userns=keep-id:uid=1001,gid=1001 --cgroup-manager=cgroupfs"
  MOUNT=":Z"
else
  USER_OPTS="--user $(id -u):$(id -g) -e HOME=/tmp"
  MOUNT=""
fi

echo "native/build.sh: $CONTAINER, $IMAGE, cpus=$CPUS memory=$MEMORY"
# the copy the compiler reads, not $JAR (a parallel build may replace that meanwhile)
echo "native/build.sh: input jar sha256 $(sha256sum "$WORK/dc-agent.jar" | cut -d' ' -f1)"
# shellcheck disable=SC2086
"$CONTAINER" run --rm $USER_OPTS --cpus="$CPUS" --memory="$MEMORY" \
  -v "$WORK":/project$MOUNT --entrypoint native-image "$IMAGE" \
  -J-Xmx6g -jar /project/dc-agent.jar -o /project/dc-agent

cp "$WORK/dc-agent" "$OUT"
chmod 0755 "$OUT"
echo "native/build.sh: $OUT sha256 $(sha256sum "$OUT" | cut -d' ' -f1)"
