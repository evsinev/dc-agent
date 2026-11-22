#!/usr/bin/env bash
# Create daemontools-style service directories under the agent's SERVICES_DIR so the
# operator's Service list shows real services (the agent parses each service's
# supervise/status file in Java — no daemontools binary needed), and start bin/fake-supervise,
# which applies the agent's commands (bytes written to supervise/control) to supervise/status.
#
# Per service:
#   <name>/supervise/status   18-byte daemontools status file (when / pid / paused / want)
#   <name>/supervise/control  fifo the agent writes a command byte to (read by fake-supervise)
#   <name>/supervise/ok       fifo kept open by fake-supervise  -> SUPERVISE_RUNNING
#   <name>/run                a no-op run script (so the dir looks like a real service)
#   <name>/down               (optional) "normally down" flag -> DOWN state
#
# Status file layout parsed by SuperviseStatusFileParser (18 bytes):
#   0-7  when   TAI64 seconds, big-endian  (2^62 + 10 + unixSeconds)
#   8-11 nanos  (big-endian; we use 0)
#   12-15 pid   little-endian
#   16   paused (0/1)
#   17   want   'u'(0x75) | 'd'(0x64) | 0
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib-status.sh"   # _byte / write_status

# make_service <name> <pid> <paused 0|1> <want u|d|-> <down yes|no>
make_service() {
  local name="$1" pid="$2" paused="$3" want="$4" down="$5"
  local dir="$AGENT_SERVICES_DIR/$name"
  rm -rf "$dir"
  mkdir -p "$dir/supervise" "$dir/log"
  write_status "$dir/supervise/status" "$pid" "$paused" "$want"
  mkfifo "$dir/supervise/control" "$dir/supervise/ok"   # served by fake-supervise
  # run + log/run scripts — the Service view (getServiceView) reads both.
  printf '#!/bin/sh\nexec sleep 86400\n' > "$dir/run"
  printf '#!/bin/sh\nexec multilog t ./main\n' > "$dir/log/run"
  chmod +x "$dir/run" "$dir/log/run"
  [[ "$down" == "yes" ]] && : > "$dir/down" || true
}

# a fake-supervise from an earlier run serves fifos that are about to be removed
if [[ -f "$PID_DIR/supervise.pid" ]]; then
  kill "$(cat "$PID_DIR/supervise.pid")" 2>/dev/null || true
  rm -f "$PID_DIR/supervise.pid"
fi
mkdir -p "$AGENT_SERVICES_DIR" "$PID_DIR"

#            name          pid   paused want down   -> resulting state
make_service echo-svc      4321  0      u    no    # UP
make_service sample-queue  5555  1      u    no    # UP_PAUSED
make_service demo-timer    0     0      -    yes   # DOWN (normally down)

nohup "$VERIFY_DIR/bin/fake-supervise" "$AGENT_SERVICES_DIR"/*/ > "$LOG_DIR/fake-supervise.log" 2>&1 &
echo $! > "$PID_DIR/supervise.pid"

log "Created daemontools services under $AGENT_SERVICES_DIR:"
for d in "$AGENT_SERVICES_DIR"/*/; do log "  - $(basename "$d")"; done
