#!/bin/sh
# Builds the Landlock launcher in the pinned Mandrel builder image (UBI 9: gcc, glibc 2.34 — runs on
# EL9 and newer, like the native agent):
#
#   native/landlock/build.sh <out-dir>
#
# <out-dir> gets dc-agent-landlock (released), plus for native/landlock/test.sh only:
# dc-agent-landlock-test (no seccomp, to test the Landlock scopes it would mask) and
# dc-agent-landlock-probe (performs one syscall per operation). Environment: CONTAINER.
set -eu
. "$(dirname "$0")/../image.sh"
SRC=$(cd "$(dirname "$0")" && pwd)
OUT=${1:?usage: native/landlock/build.sh <out-dir>}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
CONTAINER=${CONTAINER:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$SRC/dc-agent-landlock.c" "$SRC/dc-agent-landlock-probe.c" "$WORK/"

if [ "$CONTAINER" = podman ]; then
  USER_OPTS="--userns=keep-id:uid=1001,gid=1001 --cgroup-manager=cgroupfs"
  MOUNT=":Z"
else
  USER_OPTS="--user $(id -u):$(id -g) -e HOME=/tmp"
  MOUNT=""
fi
FLAGS="-O2 -Wall -Wextra -Werror -D_FORTIFY_SOURCE=2 -fstack-protector-strong -fPIE -pie -Wl,-z,relro,-z,now"
# shellcheck disable=SC2086
"$CONTAINER" run --rm $USER_OPTS --network=none -v "$WORK":/src$MOUNT --entrypoint sh "$MANDREL_IMAGE" -c "
  set -e; cd /src
  gcc $FLAGS -o dc-agent-landlock dc-agent-landlock.c
  gcc $FLAGS -DDC_AGENT_LANDLOCK_TEST_NO_SECCOMP -o dc-agent-landlock-test dc-agent-landlock.c
  gcc $FLAGS -o dc-agent-landlock-probe dc-agent-landlock-probe.c -lpthread"

for f in dc-agent-landlock dc-agent-landlock-test dc-agent-landlock-probe; do
  cp "$WORK/$f" "$OUT/$f"
  chmod 0755 "$OUT/$f"
done
echo "native/landlock/build.sh: $OUT/dc-agent-landlock sha256 $(sha256sum "$OUT/dc-agent-landlock" | cut -d' ' -f1)"
