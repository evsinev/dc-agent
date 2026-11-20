#!/bin/sh
# Tests the Landlock launcher in a disposable container (no network, its own loopback):
#
#   native/landlock/test.sh <dir from native/landlock/build.sh>
#
# Needs a kernel with Landlock ABI 4+ (6 for the scope checks) and a container runtime whose seccomp
# profile allows the landlock syscalls. Environment: CONTAINER.
set -eu
. "$(dirname "$0")/../image.sh"
SRC=$(cd "$(dirname "$0")" && pwd)
BIN=$(cd "${1:?usage: native/landlock/test.sh <bin-dir>}" && pwd)
CONTAINER=${CONTAINER:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$SRC/in-container.sh" "$SRC/listeners.py" "$WORK/"
for f in dc-agent-landlock dc-agent-landlock-test dc-agent-landlock-probe; do cp "$BIN/$f" "$WORK/$f"; done

RUNTIME_OPTS=""
RO=":ro"
if [ "$CONTAINER" = podman ]; then
  RUNTIME_OPTS="--cgroup-manager=cgroupfs"
  RO=":ro,Z"
fi
# shellcheck disable=SC2086
"$CONTAINER" run --rm $RUNTIME_OPTS --user 0 --network=none --cpus=2 --memory=1g \
  -v "$WORK":/ll$RO --entrypoint sh "$MANDREL_IMAGE" /ll/in-container.sh
