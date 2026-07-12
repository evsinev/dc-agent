#!/usr/bin/env bash
# Read/write daemontools 18-byte supervise/status files. Pure helpers — safe to source
# (no `set` changes, no side effects). Used by setup-services.sh and bin/svc.
#
# Layout (parsed by the agent's SuperviseStatusFileParser):
#   0-7  when   TAI64 seconds, big-endian  (0x4000000000000000 + unixSeconds)
#   8-11 nanos  (little-endian; we use 0)
#   12-15 pid   little-endian
#   16   paused (0/1)
#   17   want   'u'(0x75) | 'd'(0x64) | 0

# Emit one byte given a decimal value 0-255, as a printf octal escape.
_byte() { printf '\'"$(printf '%03o' "$(( $1 & 0xff ))")"; }

# write_status <file> <pid> <paused 0|1> <want u|d|->
write_status() {
  local file="$1" pid="$2" paused="$3" want="$4" now tai i
  now="$(date +%s)"
  tai=$(( (1 << 62) + now ))                      # TAI64 label = 2^62 + unix seconds
  mkdir -p "$(dirname "$file")"
  {
    for i in 7 6 5 4 3 2 1 0; do _byte $(( (tai >> (8 * i)) & 0xff )); done   # when (big-endian)
    _byte 0; _byte 0; _byte 0; _byte 0                                        # nanoseconds
    _byte "$(( pid & 0xff ))"; _byte "$(( (pid >> 8) & 0xff ))"
    _byte "$(( (pid >> 16) & 0xff ))"; _byte "$(( (pid >> 24) & 0xff ))"      # pid (little-endian)
    _byte "$paused"                                                          # paused
    case "$want" in u) _byte 117 ;; d) _byte 100 ;; *) _byte 0 ;; esac        # want
  } > "$file"
}

# One byte (decimal) at offset $1 of file $2 (empty if out of range / missing).
_status_byte() { od -An -tu1 -j"$1" -N1 "$2" 2>/dev/null | tr -d ' '; }

# status_pid <file> -> current pid (0 if absent)
status_pid() {
  local f="$1" b12 b13 b14 b15
  [[ -f "$f" ]] || { echo 0; return; }
  b12=$(_status_byte 12 "$f"); b13=$(_status_byte 13 "$f")
  b14=$(_status_byte 14 "$f"); b15=$(_status_byte 15 "$f")
  echo $(( ${b12:-0} + ${b13:-0} * 256 + ${b14:-0} * 65536 + ${b15:-0} * 16777216 ))
}

# status_paused <file> -> 0|1
status_paused() {
  local f="$1" b
  [[ -f "$f" ]] || { echo 0; return; }
  b=$(_status_byte 16 "$f"); echo $(( ${b:-0} != 0 ? 1 : 0 ))
}

# status_want <file> -> u|d|-
status_want() {
  local f="$1" b
  [[ -f "$f" ]] || { echo '-'; return; }
  b=$(_status_byte 17 "$f"); case "${b:-0}" in 117) echo u ;; 100) echo d ;; *) echo '-' ;; esac
}
