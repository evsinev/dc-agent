#!/bin/sh
# Runs inside the container started by native/landlock/test.sh (as root: Landlock and seccomp bind
# root too, and DAC then allows every operation, so a refusal can only come from the sandbox).
set -u
L=/ll/dc-agent-landlock
T=/ll/dc-agent-landlock-test
P=/ll/dc-agent-landlock-probe
W=/work/rw     # --rw
R=/work/ro     # --ro
O=/work/out    # no rule
LDSO=$(readlink -f /lib64/ld-linux-x86-64.so.2)
PASS=0
FAIL=0

pass() { echo "PASS $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $*"; FAIL=$((FAIL + 1)); }

# expect <probe output> <op> <result regex> <what>
expect() {
  if printf '%s\n' "$1" | grep -Eqx "$2 $3"; then
    pass "$4: $2 $3"
  else
    fail "$4: $2 — want '$3', got '$(printf '%s\n' "$1" | grep -E "^$2 " | head -1)'"
  fi
}

mkdir -p "$W" "$R" "$O"
echo data > "$R/file"
echo data > "$O/file"
python3 /ll/listeners.py &
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s /work/listeners.pid ] && break; sleep 0.5; done
LISTENERS=$(cat /work/listeners.pid)

ABI=$(python3 -c 'import ctypes; l=ctypes.CDLL(None); l.syscall.restype=ctypes.c_long; print(l.syscall(444, None, 0, 1))')
echo "kernel Landlock ABI: $ABI"
if [ "$ABI" -lt 4 ]; then
  echo "FAIL the kernel has no Landlock ABI 4+ — the launcher would refuse to start anything"
  exit 1
fi

# the same probe sequence for every run; fd 3 is opened by the shell — the launcher must close it
OPS="write $W/x write $R/x write $O/x read $R/file read $O/file mkdir $W/d symlink /etc/os-release $W/l
     rename-out $W/f $O/f exec /usr/bin/true memfd-exec memfd-mmap-exec ldso-exec $LDSO $W
     tcp-connect 127.0.0.1 19001 tcp-fastopen 127.0.0.1 19001 tfo-sendmsg 127.0.0.1 19001
     tfo-sendmmsg 127.0.0.1 19001 tfo-connect-opt 127.0.0.1 19001 tcp-bind 18052 listen-autobind sctp-socket
     udp-send 127.0.0.1 19002 unix-connect $O/unix.sock socketpair kill $LISTENERS read-fd 3 nnp
     fd0-listening unshare-user io-uring thread read /proc/self/net/tcp nested-ruleset $O/n"
BASE="--rx $P --rx $LDSO --ro /usr/lib64 --ro /etc/ld.so.cache --rw /dev/null --rw $W --ro $R"

# ---- 0. control: without the launcher everything DAC allows root must work, or the checks prove nothing
: > /work/udp-received
# shellcheck disable=SC2086
OUT=$($P $OPS 3< "$O/file")
echo "$OUT" | sed 's/^/  control: /'
# container-dependent (its seccomp profile, modules, sysctl) or launcher-only: not part of the control
bad=$(printf '%s\n' "$OUT" | grep -Ev '^(sctp-socket|io-uring|unshare-user|tcp-fastopen|tfo-[a-z-]+|fd0-listening|nnp) ' \
      | grep -Ev ' (ALLOWED|ALLOWED port [0-9]+)$')
[ -z "$bad" ] && pass "control: every operation works without the launcher" || fail "control: refused without the launcher: $bad"
[ "$(printf '%s\n' "$OUT" | wc -l)" -ge 31 ] && pass "control: all operations ran" || fail "control: some operations did not run"
for op in unshare-user io-uring sctp-socket; do echo "INFO control, this container: $(printf '%s\n' "$OUT" | grep "^$op ")"; done
grep -q probe /work/udp-received && pass "control: the UDP packet arrived" || fail "control: no UDP packet without the launcher"

# ---- 1. the released launcher
: > /work/udp-received
# shellcheck disable=SC2086
OUT=$($L --listen-tcp 127.0.0.1:18051 $BASE -- $P $OPS 2>/work/launcher.err 3< "$O/file")
echo "$OUT" | sed 's/^/  launcher: /'
grep -q "Landlock ABI $ABI" /work/launcher.err && pass "launcher: logs the ABI" || fail "launcher: no ABI line"
expect "$OUT" "write" "ALLOWED" "launcher rw"   # the first write line is $W/x
[ "$(printf '%s\n' "$OUT" | grep -c '^write DENIED EACCES$')" -eq 2 ] && pass "launcher: write in ro and outside DENIED EACCES" || fail "launcher: write outside rw"
[ "$(printf '%s\n' "$OUT" | grep -c '^read ALLOWED$')" -eq 1 ] && pass "launcher: read only the ro file" || fail "launcher: read"
[ "$(printf '%s\n' "$OUT" | grep -c '^read DENIED EACCES$')" -eq 2 ] && pass "launcher: read outside and /proc/self/net/tcp DENIED" || fail "launcher: read outside"
expect "$OUT" "mkdir" "ALLOWED" "launcher"
expect "$OUT" "symlink" "DENIED EACCES" "launcher: no symlinks in rw"
expect "$OUT" "rename-out" "DENIED (EACCES|EXDEV)" "launcher: rw → outside"
expect "$OUT" "exec" "DENIED EACCES" "launcher: exec outside rx"
expect "$OUT" "memfd-exec" "DENIED EPERM" "launcher: memfd_create"
expect "$OUT" "memfd-mmap-exec" "DENIED EPERM" "launcher: memfd_create"
expect "$OUT" "ldso-exec" "ALLOWED" "launcher: KNOWN GAP ld.so <ELF in rw> (closed by NoExecPaths under systemd)"
expect "$OUT" "tcp-connect" "DENIED EACCES" "launcher: Landlock connect"
expect "$OUT" "tcp-fastopen" "DENIED EPERM" "launcher: TCP Fast Open"
expect "$OUT" "tfo-sendmsg" "DENIED EPERM" "launcher: TCP Fast Open (sendmsg)"
expect "$OUT" "tfo-sendmmsg" "DENIED EPERM" "launcher: TCP Fast Open (sendmmsg)"
expect "$OUT" "tfo-connect-opt" "DENIED EPERM" "launcher: TCP_FASTOPEN_CONNECT"
expect "$OUT" "tcp-bind" "DENIED EPERM" "launcher: bind"
expect "$OUT" "listen-autobind" "DENIED EPERM" "launcher: listen without bind"
expect "$OUT" "sctp-socket" "DENIED EPERM" "launcher: SCTP"
expect "$OUT" "udp-send" "DENIED EPERM" "launcher: UDP"
sleep 0.3; grep -q probe /work/udp-received && fail "launcher: a UDP packet arrived" || pass "launcher: no UDP packet arrived"
expect "$OUT" "unix-connect" "DENIED EPERM" "launcher: AF_UNIX"
expect "$OUT" "socketpair" "DENIED EPERM" "launcher"
expect "$OUT" "read-fd" "DENIED EBADF" "launcher: inherited fd 3 closed"
expect "$OUT" "nnp" "VALUE 1" "launcher: no_new_privs"
expect "$OUT" "fd0-listening" "VALUE 1" "launcher: fd 0 is the listener"
expect "$OUT" "unshare-user" "DENIED EPERM" "launcher: namespaces"
expect "$OUT" "io-uring" "DENIED EPERM" "launcher"
expect "$OUT" "thread" "ALLOWED" "launcher: threads (clone3 → clone)"
expect "$OUT" "nested-ruleset" "DENIED EACCES" "launcher: a nested ruleset widens nothing"
if [ "$ABI" -ge 6 ]; then
  expect "$OUT" "kill" "DENIED EPERM" "launcher: signal scope"
fi

# --rd: list and open directories, read no file (the agent's view of service roots, fsync of a parent)
D=/work/rd
mkdir -p "$D/sub" && echo secret > "$D/sub/run"
# shellcheck disable=SC2086
OUT=$($L --listen-tcp 127.0.0.1:18057 $BASE --rd $D -- $P list $D list $D/sub fsync-dir $D/sub read $D/sub/run write $D/x list $O 2>/dev/null)
echo "$OUT" | sed 's/^/  rd: /'
[ "$(printf '%s\n' "$OUT" | grep -c '^list ALLOWED$')" -eq 2 ] && pass "launcher --rd: lists the dir and below" || fail "launcher --rd: list: $OUT"
expect "$OUT" "fsync-dir" "ALLOWED" "launcher --rd: opens a dir to fsync it"
expect "$OUT" "read" "DENIED EACCES" "launcher --rd: reads no file"
expect "$OUT" "write" "DENIED EACCES" "launcher --rd: writes nothing"
[ "$(printf '%s\n' "$OUT" | tail -1)" = "list DENIED EACCES" ] && pass "launcher --rd: nothing outside" || fail "launcher --rd: outside: $OUT"

# an allowed port
# shellcheck disable=SC2086
OUT=$($L --listen-tcp 127.0.0.1:18051 $BASE --connect-tcp 19001 -- $P tcp-connect 127.0.0.1 19001 tcp-connect 127.0.0.1 19002 2>/dev/null)
[ "$(printf '%s\n' "$OUT" | head -1)" = "tcp-connect ALLOWED" ] && pass "launcher: --connect-tcp 19001 allows it" || fail "launcher: --connect-tcp: $OUT"
[ "$(printf '%s\n' "$OUT" | tail -1)" = "tcp-connect DENIED EACCES" ] && pass "launcher: …and no other port" || fail "launcher: other port: $OUT"

# ---- 2. the test build without seccomp: what Landlock alone does (scopes) and does not (UDP, unix paths)
if [ "$ABI" -ge 6 ]; then
  # shellcheck disable=SC2086
  OUT=$($T --listen-tcp 127.0.0.1:18053 $BASE -- $P abstract-connect llprobe-abstract kill $LISTENERS unix-connect $O/unix.sock udp-send 127.0.0.1 19002 2>/dev/null)
  echo "$OUT" | sed 's/^/  landlock only: /'
  expect "$OUT" "abstract-connect" "DENIED EPERM" "landlock only: abstract unix scope"
  expect "$OUT" "kill" "DENIED EPERM" "landlock only: signal scope"
  expect "$OUT" "unix-connect" "ALLOWED" "landlock only: KNOWN GAP unix path (closed by seccomp)"
  expect "$OUT" "udp-send" "ALLOWED" "landlock only: KNOWN GAP UDP (closed by seccomp)"
  # TCP Fast Open past a Landlock connect rule is a known kernel bug; seccomp refuses MSG_FASTOPEN
  # anyway — this only tells whether this kernel has the fix
  # shellcheck disable=SC2086
  TFO=$($T --listen-tcp 127.0.0.1:18055 $BASE -- $P tcp-fastopen 127.0.0.1 19001 2>/dev/null)
  echo "INFO landlock only, this kernel: $TFO (DENIED = the kernel has the TCP Fast Open fix)"
else
  echo "SKIP scopes: Landlock ABI $ABI < 6"
fi

# ---- 3. fail closed: exit 111, the program never runs. Each case is the working command below with
# one thing broken, so a 111 can only come from that thing (not from a probe that could not run).
OK="--listen-tcp 127.0.0.1:18054 --rx $P --rx $LDSO --ro /usr/lib64 --ro /etc/ld.so.cache --rw /dev/null"
# shellcheck disable=SC2086
out=$($L $OK -- $P noop 2>/dev/null); rc=$?
[ $rc -eq 0 ] && [ "$out" = "noop ALLOWED" ] && pass "positive twin: the working command runs the probe" || fail "positive twin: rc=$rc out='$out'"
# shellcheck disable=SC2086
out=$($L $OK -- $P noop fd0-listening 0<&- 1>&1 2>/dev/null); rc=$?
[ $rc -eq 0 ] && [ "$out" = "$(printf 'noop ALLOWED\nfd0-listening VALUE 1')" ] && pass "a closed stdin: still starts, fd 0 is the listener" || fail "closed stdin: rc=$rc out='$out'"

refuses() {
  what=$1; shift
  out=$("$@" 2>/dev/null); rc=$?
  if [ $rc -eq 111 ] && [ -z "$out" ]; then pass "refuses: $what"; else fail "refuses: $what (rc=$rc, out='$out')"; fi
}
# shellcheck disable=SC2086
{
refuses "no --"                  $L $OK $P noop
refuses "a missing path"         $L $OK --ro /nonexistent -- $P noop
refuses "a relative path"        $L $OK --ro work -- $P noop
refuses "--min-abi above kernel" $L $OK --min-abi 99 -- $P noop
refuses "no --listen-tcp"        $L --rx $P --rx $LDSO --ro /usr/lib64 --ro /etc/ld.so.cache --rw /dev/null -- $P noop
refuses "a relative program"     $L $OK -- probe noop
refuses "a bad connect port"     $L $OK --connect-tcp abc -- $P noop
refuses "an unknown option"      $L $OK --rwx /tmp -- $P noop
refuses "--rd on a file"         $L $OK --rd /etc/ld.so.cache -- $P noop
refuses "port 0"                 $L --listen-tcp 127.0.0.1:0 ${OK#--listen-tcp 127.0.0.1:18054} -- $P noop
refuses "port 70000"             $L --listen-tcp 127.0.0.1:70000 ${OK#--listen-tcp 127.0.0.1:18054} -- $P noop
refuses "a busy port"            $L --listen-tcp 127.0.0.1:19001 ${OK#--listen-tcp 127.0.0.1:18054} -- $P noop
refuses "a hostname"             $L --listen-tcp localhost:18054 ${OK#--listen-tcp 127.0.0.1:18054} -- $P noop
refuses "two --listen-tcp"       $L $OK --listen-tcp 127.0.0.1:18056 -- $P noop
}

kill "$LISTENERS" 2>/dev/null
echo "landlock test: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
