#!/bin/sh
# Smoke of the agent, the same for the JVM jar and the native binary — run inside a disposable
# Linux container as its root (native/smoke/run.sh), never on a host with a real agent.
#   AGENT_KIND=jar     /smoke/dc-agent.jar, started with java (JAVA_AGENT_OPTS: e.g. the tracing agent)
#   AGENT_KIND=native  /smoke/dc-agent
set -u
CFG=/opt/dc-agent/config
mkdir -p /opt/dc-agent
if [ "${AGENT_KIND:-jar}" = native ]; then
  cp /smoke/dc-agent /opt/dc-agent/dc-agent
  AGENT=/opt/dc-agent/dc-agent
else
  cp /smoke/dc-agent.jar /opt/dc-agent/dc-agent.jar
  AGENT="java ${JAVA_AGENT_OPTS:-} -jar /opt/dc-agent/dc-agent.jar"
fi
echo "smoke: $AGENT"
KEY=smoke-key-0123456789abcdef0123456789abcdef
pass=0; fail=0
ok()   { pass=$((pass+1)); echo "PASS $1"; }
bad()  { fail=$((fail+1)); echo "FAIL $1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got '$2', want '$3')"; fi; }

mkdir -p $CFG /opt/site /tmp/zip && cd /opt/dc-agent
echo hello > /tmp/zip/index.html && (cd /tmp/zip && jar cfM /tmp/site.zip index.html)

# 1. the paynet-react leftovers: no type, dir=/root, not JSON -> check-config 1, start refused
printf '{"apiKeys":{"i4153key":"i4153"},"dir":"/root"}' > $CFG/i4153.json
printf 'Z2678PROBE ../dc-agent/config/zz2678probe.json' > $CFG/zz2678probe.json
$AGENT check-config > /tmp/cc1.txt 2>&1; check "check-config refuses attacker leftovers" $? 1
grep -q 'i4153.json: no "type"' /tmp/cc1.txt && ok "reports missing type" || bad "reports missing type"
grep -q 'zz2678probe.json: not valid JSON' /tmp/cc1.txt && ok "reports probe file" || bad "reports probe file"
timeout 60 $AGENT > /tmp/start1.txt 2>&1; check "agent refuses to start" $? 1
test ! -e $CFG/.dc-agent.lock && ok "nothing written on refusal" || bad "lock created on refusal"
rm -f $CFG/i4153.json $CFG/zz2678probe.json

# 2. CONFIG_DIR elsewhere refuses the start
CONFIG_DIR=/etc/dc-agent timeout 60 $AGENT > /tmp/start2.txt 2>&1; check "CONFIG_DIR elsewhere refuses start" $? 1

# 3. dir outside COMMAND_DIRS
printf '{"type":"ZIP_ARCHIVE","apiKeys":{"%s":"ci"},"dir":"/etc/cron.d"}' "$KEY" > $CFG/cron.json
$AGENT check-config > /tmp/cc3.txt 2>&1; check "dir /etc/cron.d refused" $? 1
rm -f $CFG/cron.json

# 4. a good plaintext config + YAML: check-config reports, writes nothing
printf '{"type":"ZIP_ARCHIVE","apiKeys":{"%s":"ci"},"dir":"/opt/site"}' "$KEY" > $CFG/site.json
printf 'type: ZIP_ARCHIVE\napiKeys:\n  %s: ci\ndir: /opt/site\n' "$KEY" > $CFG/menu.yml
chmod 640 $CFG/site.json
before=$(sha256sum $CFG/site.json $CFG/menu.yml)
$AGENT check-config > /tmp/cc4.txt 2>&1; check "check-config ok" $? 0
grep -q "menu.yml: 1 plaintext key(s) hashed, written as menu.json" /tmp/cc4.txt && ok "reports the migration" || bad "reports the migration"
check "check-config wrote nothing" "$(sha256sum $CFG/site.json $CFG/menu.yml)" "$before"

# 5. start: keys hashed, mode kept, yml -> json
$AGENT > /tmp/agent.log 2>&1 &
AGENT_PID=$!
for i in $(seq 1 60); do curl -s -o /dev/null http://127.0.0.1:8051/dc-agent/health && break; sleep 1; done
HASH=$(printf '%s\n' "$KEY" | $AGENT hash-key)
# exactly one line, the hash: anything else on stdout (a log line) would end up in a config
echo "$HASH" | grep -qxE 'sha256:[0-9a-f]{64}' && [ "$(echo "$HASH" | wc -l)" -eq 1 ] && ok "hash-key prints only the hash" || bad "hash-key prints only the hash (got '$HASH')"
grep -q "$HASH" $CFG/site.json && ok "site.json hashed" || bad "site.json hashed"
grep -q "$KEY" $CFG/site.json $CFG/menu.json && bad "plaintext key left on disk" || ok "no plaintext key on disk"
test ! -e $CFG/menu.yml && test -e $CFG/menu.json && ok "menu.yml -> menu.json" || bad "menu.yml -> menu.json"
check "mode kept" "$(stat -c %a $CFG/site.json)" 640
ls -a $CFG | grep -q 'dc-agent-tmp' && bad "temp file left" || ok "no temp file left"

# 6. calls
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/site.zip -H "api-key: $KEY" http://127.0.0.1:8051/dc-agent/zip-archive/site)
check "the CI key deploys" "$code" 200
check "file deployed" "$(cat /opt/site/index.html 2>/dev/null)" hello
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/site.zip -H "api-key: $HASH" http://127.0.0.1:8051/dc-agent/zip-archive/site)
check "the stolen hash is not a key" "$code" 401
# a config dropped while the agent runs, as the attacker did; first its positive twin: hashed key,
# allowed dir, with type -> works; then the same without type -> 401 (only the type differs)
DROPKEY=i3909key-0123456789abcdef0123456789ab
DROPHASH=$(printf '%s\n' "$DROPKEY" | $AGENT hash-key)
printf '{"type":"ZIP_ARCHIVE","apiKeys":{"%s":"x"},"dir":"/opt/dropped"}' "$DROPHASH" > $CFG/i3909.json
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/site.zip -H "api-key: $DROPKEY" http://127.0.0.1:8051/dc-agent/zip-archive/i3909)
check "dropped config twin with type: 200" "$code" 200
printf '{"apiKeys":{"%s":"x"},"dir":"/opt/dropped"}' "$DROPHASH" > $CFG/i3909.json
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/site.zip -H "api-key: $DROPKEY" http://127.0.0.1:8051/dc-agent/zip-archive/i3909)
check "dropped config without type: 401" "$code" 401
printf '{"type":"ZIP_ARCHIVE","apiKeys":{"%s":"x"},"dir":"/root"}' "$(printf '%s\n' i3909key-0123456789abcdef0123456789ab | $AGENT hash-key)" > $CFG/i3909.json
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/site.zip -H "api-key: i3909key-0123456789abcdef0123456789ab" http://127.0.0.1:8051/dc-agent/zip-archive/i3909)
check "dropped config with dir=/root: 401" "$code" 401
test ! -e /root/index.html && ok "/root untouched" || bad "/root written"
rm -f $CFG/i3909.json

# 7. a second agent on the same dir
WEB_SERVER_PORT=8052 timeout 60 $AGENT > /tmp/start7.txt 2>&1; check "second agent refused" $? 1
grep -q "another dc-agent" /tmp/start7.txt && ok "lock named" || bad "lock named"

# 8. restart changes no byte
kill $AGENT_PID; wait $AGENT_PID 2>/dev/null
after1=$(sha256sum $CFG/site.json $CFG/menu.json)
$AGENT > /tmp/agent2.log 2>&1 &
AGENT_PID=$!
up=no
for i in $(seq 1 60); do curl -s -o /dev/null http://127.0.0.1:8051/dc-agent/health && up=yes && break; sleep 1; done
check "second start is up" "$up" yes
check "second start changes no byte" "$(sha256sum $CFG/site.json $CFG/menu.json)" "$after1"
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/site.zip -H "api-key: $KEY" http://127.0.0.1:8051/dc-agent/zip-archive/site)
check "after the restart the CI key still deploys" "$code" 200
kill $AGENT_PID; wait $AGENT_PID 2>/dev/null

grep -E "Migrated|Refusing" /tmp/agent.log /tmp/start1.txt | sed 's/^/LOG /' | head -8
echo "RESULT pass=$pass fail=$fail"
test $fail -eq 0
