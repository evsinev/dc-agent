#!/bin/sh
# The paths a native image breaks quietly — run like agent-smoke.sh (same AGENT_KIND), after it, in
# the same disposable container. Needs /smoke/TlsServer.java and /smoke/OidcIssuer.java; daemontools binaries in /dt
# (svscan, supervise; svstat for the checks here — optional: without them those checks are SKIP, never PASS).
#   - control plane: a command of every type (every Command*Request deserialized), get, list, backup,
#     service list, metrics;
#   - /app-status with its token: fields, not "{}";
#   - daemontools through the supervise fifos (no svc/svstat in the agent): a jar deploy stops and
#     starts a supervised service, a control-plane action stops it;
#   - docker/check with securityContext.passwdEntry (runs `docker --version`);
#   - TLS: a CA made here, after the build, trusted through the host store; another CA refused;
#   - zip-archive-version with an HTTPS reload, save-artifact, zip-dirs, fetch-url;
#   - GitLab ID tokens: fetch-jwks from a local issuer, Bearer against GITLAB_JWT rules, audit lines;
#   - native only: started through a PATH link outside its home, a command touching the link refused.
set -u
pass=0; fail=0; skip=0
ok()   { pass=$((pass+1)); echo "PASS $1"; }
bad()  { fail=$((fail+1)); echo "FAIL $1"; }
skp()  { skip=$((skip+1)); echo "SKIP $1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got '$2', want '$3')"; fi; }
has()  { if grep -q -- "$3" "$2"; then ok "$1"; else bad "$1 (no '$3' in $2: $(head -c 300 "$2"))"; fi; }

CFG=/opt/dc-agent/config
KEY=extended-key-0123456789abcdef0123456789abcd
CP_TOKEN=cp-token-0123456789abcdef0123456789abcdef
STATUS_TOKEN=status-token-0123456789abcdef0123456789ab
BASE=http://127.0.0.1:8051/dc-agent
export CONTROL_PLANE_ENABLED=true CONTROL_PLANE_TOKEN=$CP_TOKEN APP_STATUS_TOKEN=$STATUS_TOKEN
export SERVICES_DIR=/service SERVICES_DEFINITION_DIR=/etc/service.d CONTAINER_RUNTIME=podman

# a fresh state after agent-smoke.sh (which stops its own agents)
rm -rf $CFG /opt/dc-agent/keys /opt/oidc /opt/site /opt/cp /opt/zav /opt/zav2 /opt/sa /opt/zd /opt/testsvc /service /etc/service.d /srv
mkdir -p $CFG /service /etc/service.d /var/log/testsvc /opt/testsvc /tmp/ext && cd /tmp/ext

if [ "${AGENT_KIND:-jar}" = native ]; then
  # started through PATH, via a link outside the agent's home
  mkdir -p /srv/releases /srv/launcher /srv/launcher2
  cp /smoke/dc-agent /srv/releases/dc-agent
  ln -s /srv/releases/dc-agent /srv/launcher/dc-agent
  export PATH=/srv/launcher:$PATH
  AGENT=dc-agent
else
  AGENT="java ${JAVA_AGENT_OPTS:-} -Djavax.net.ssl.trustStore=/etc/pki/java/cacerts -jar /opt/dc-agent/dc-agent.jar"
fi
HASH=$(printf '%s\n' "$KEY" | $AGENT hash-key)

# --- TLS material, made now (after the build): a trusted CA in the host store, a rogue one not
mk_ca() {  # $1 name
  keytool -genkeypair -alias ca -keyalg EC -groupname secp256r1 -dname "CN=$1" -ext bc:c -validity 2 \
    -keystore "$1.p12" -storepass smokepass -storetype PKCS12 >/dev/null 2>&1
  keytool -exportcert -alias ca -keystore "$1.p12" -storepass smokepass -rfc -file "$1.pem" >/dev/null 2>&1
}
mk_server() {  # $1 ca name, $2 server keystore
  keytool -genkeypair -alias server -keyalg EC -groupname secp256r1 -dname "CN=localhost" -validity 2 \
    -keystore "$2" -storepass smokepass -storetype PKCS12 >/dev/null 2>&1
  keytool -certreq -alias server -keystore "$2" -storepass smokepass -file "$2.csr" >/dev/null 2>&1
  keytool -gencert -alias ca -keystore "$1.p12" -storepass smokepass -infile "$2.csr" -outfile "$2.pem" -rfc \
    -ext san=dns:localhost,ip:127.0.0.1 -validity 2 >/dev/null 2>&1
  keytool -importcert -alias ca -file "$1.pem" -keystore "$2" -storepass smokepass -noprompt >/dev/null 2>&1
  keytool -importcert -alias server -file "$2.pem" -keystore "$2" -storepass smokepass -noprompt >/dev/null 2>&1
}
mk_ca smoke-ca; mk_server smoke-ca good.p12
mk_ca rogue-ca; mk_server rogue-ca rogue.p12
# the way a CA is added on an EL host: an anchor plus update-ca-trust, which rewrites /etc/pki/java/cacerts
cp smoke-ca.pem /etc/pki/ca-trust/source/anchors/smoke-ca.pem && update-ca-trust extract \
  && keytool -list -keystore /etc/pki/java/cacerts -storepass changeit 2>/dev/null | grep -qi smoke-ca \
  && ok "CA added to the host store after the build" || bad "CA added to the host store after the build"
java /smoke/TlsServer.java 8443 good.p12 smokepass > /tmp/tls-good.log 2>&1 &
java /smoke/TlsServer.java 8444 rogue.p12 smokepass > /tmp/tls-rogue.log 2>&1 &
for i in $(seq 1 60); do grep -q listening /tmp/tls-good.log && grep -q listening /tmp/tls-rogue.log && break; sleep 1; done

# --- configs by hand: the reload over HTTPS, a supervised service to deploy to
printf '{"type":"ZIP_ARCHIVE_VERSION","apiKeys":{"%s":"ci"},"dir":"/opt/zav","versionFile":"/opt/zav/current","reloadUrl":"https://localhost:8443/reload?version=${version}"}' "$HASH" > $CFG/zav.json
printf '{"type":"ZIP_ARCHIVE_VERSION","apiKeys":{"%s":"ci"},"dir":"/opt/zav2","versionFile":"/opt/zav2/current","reloadUrl":"https://localhost:8444/reload?version=${version}"}' "$HASH" > $CFG/zavrogue.json
# waitUrl: without one the deploy still waits waitDuration and answers 417 (WaitUrlCommand does not
# skip an empty URL, unlike the docs say) — here it also checks TLS of the wait
printf '{"type":"JAR","apiKeys":{"%s":"ci"},"jarFilename":"/opt/testsvc/app.jar","serviceName":"testsvc","waitUrl":"https://localhost:8443/health","waitDuration":"30s"}' "$HASH" > $CFG/testsvc.json

echo jar > /tmp/app.jar   # the body of the jar deploy and of save-artifact

DT=no
if [ -x /dt/svscan ]; then
  DT=yes
  mkdir -p /service/testsvc
  printf '#!/bin/sh\nexec sleep 1000000\n' > /service/testsvc/run && chmod +x /service/testsvc/run
  : > /var/log/testsvc/current
  PATH=/dt:$PATH /dt/svscan /service > /tmp/svscan.log 2>&1 &
  for i in $(seq 1 30); do /dt/svstat /service/testsvc 2>/dev/null | grep -q ' up ' && break; sleep 1; done
fi

# a fake docker for docker/check: the version line of podman (the agent looks in /usr/bin,
# /usr/local/bin and /bin, not in PATH)
printf '#!/bin/sh\necho "podman version 5.0.0"\n' > /usr/local/bin/docker && chmod +x /usr/local/bin/docker

$AGENT > /tmp/ext-agent.log 2>&1 &
AGENT_PID=$!
up=no
for i in $(seq 1 60); do curl -s -o /dev/null $BASE/health && up=yes && break; sleep 1; done
check "agent up with the control plane" "$up" yes
echo "INFO $AGENT_KIND RSS after start: $(grep VmRSS /proc/$AGENT_PID/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2-)"

cp_post() {  # $1 path, $2 json body -> body to /tmp/cp.json, prints the status
  curl -s -o /tmp/cp.json -w '%{http_code}' -X POST -H "Authorization: Bearer $CP_TOKEN" \
    -H 'Content-Type: application/json' --data "$2" "$BASE/control-plane/api/$1"
}
keys() { printf '"apiKeys":{"keep":[],"add":[{"key":"%s","owner":"ci"}]}' "$KEY"; }

# --- control plane: a command of every type
create() {  # $1 type path, $2 name, $3 config json
  code=$(cp_post "command/create/$1" "{\"name\":\"$2\",\"config\":$3,$(keys)}")
  if [ "$code" = 200 ] && grep -q CREATED /tmp/cp.json; then ok "control plane creates $1"; else bad "control plane creates $1 ($code: $(head -c 300 /tmp/cp.json))"; fi
}
create jar                 cpjar   '{"jarFilename":"/opt/cp/jar/app.jar","serviceName":"cpjar"}'
create war                 cpwar   '{"warFilename":"/opt/cp/war/app.war","serviceName":"cpwar"}'
create node                cpnode  '{"jarFilename":"/opt/cp/node/bundle.zip","serviceName":"cpnode"}'
create save-artifact       cpsa    '{"dir":"/opt/cp/sa","extension":"bin"}'
create zip-archive         cpza    '{"dir":"/opt/cp/za"}'
create zip-dirs            cpzd    '{"dir":"/opt/cp/zd","delete":true}'
create fetch-url           fetch-url '{}'
create docker              dc-docker '{}'
create zip-archive-version cpzav   '{"dir":"/opt/cp/zav","versionFile":"/opt/cp/zav/current","reloadUrl":"https://localhost:8443/reload?version=${version}"}'
code=$(cp_post command/get '{"name":"cpzd"}'); check "control plane get" "$code" 200; has "get carries the fields" /tmp/cp.json '/opt/cp/zd'
code=$(cp_post command/list '{}'); check "control plane list" "$code" 200; has "list names the commands" /tmp/cp.json 'cpzav'
code=$(cp_post config/backup '{}'); check "control plane backup" "$code" 200; has "backup carries the configs" /tmp/cp.json 'cpnode.json'
code=$(cp_post command/create/zip-archive "{\"name\":\"cpbad\",\"config\":{\"dir\":\"/etc/cron.d\"},$(keys)}")
has "control plane refuses a dir out of COMMAND_DIRS" /tmp/cp.json INVALID

# --- metrics and app-status
code=$(cp_post metrics '{}'); check "metrics" "$code" 200
has "metrics carry the GC block" /tmp/cp.json '"gc"'
has "metrics say whether GC notifications are measured" /tmp/cp.json 'notificationsAvailable'
echo "INFO $AGENT_KIND GC $(grep -o '"notificationsAvailable": *[a-z]*' /tmp/cp.json)"
code=$(curl -s -o /tmp/status.json -w '%{http_code}' -H "Authorization: Bearer $STATUS_TOKEN" "$BASE/app-status/")
check "app-status" "$code" 200
fields=$(tr -cd '"' < /tmp/status.json | wc -c)
[ "$fields" -ge 4 ] && ok "app-status has fields, not {}" || bad "app-status has fields, not {} ($(head -c 200 /tmp/status.json))"

# --- daemontools through the supervise fifos
if [ $DT = yes ]; then
  code=$(cp_post service/list '{}'); check "control plane service list" "$code" 200; has "service list names testsvc" /tmp/cp.json testsvc
  before=$(/dt/svstat /service/testsvc | sed -n 's/.*(pid \([0-9]*\)).*/\1/p')
  code=$(curl -s -o /tmp/deploy.txt -w '%{http_code}' --data-binary @/tmp/app.jar -H "api-key: $KEY" $BASE/jar/testsvc)
  check "jar deploy through daemontools" "$code" 200 || true
  [ "$code" = 200 ] || { echo "DIAG deploy: $(head -c 600 /tmp/deploy.txt)"; tail -5 /tmp/ext-agent.log | sed 's/^/DIAG /'; }
  after=$(/dt/svstat /service/testsvc | sed -n 's/.*(pid \([0-9]*\)).*/\1/p')
  [ -n "$before" ] && [ -n "$after" ] && [ "$before" != "$after" ] && ok "the service was restarted (pid $before -> $after)" || bad "the service was restarted (pid '$before' -> '$after')"
  check "the jar was replaced" "$(cat /opt/testsvc/app.jar)" jar
  code=$(cp_post service/action/testsvc '{"serviceName":"testsvc","serviceAction":"DOWN"}'); check "control plane service DOWN" "$code" 200
  sleep 2; /dt/svstat /service/testsvc | grep -q ' down ' && ok "the service is down" || bad "the service is down ($(/dt/svstat /service/testsvc))"
else
  skp "daemontools checks (no /dt)"
fi

# --- docker/check with passwdEntry: runs `docker --version` (the fake one above)
mkdir -p /tmp/task && cat > /tmp/task/dc-docker.yml <<'YAML'
version: 0.0.1
name: smokesvc
image:
  name: "alma9-java-21-temurin-jre:1"
directories:
  sourceBaseDir: /opt/smokesvc
  destinationBaseDir: /opt/smokesvc
securityContext:
  runAsUser: 20500
  runAsGroup: 20500
  readOnlyRootFilesystem: true
  passwdEntry: "probe-$UID:*:$UID:$GID::/tmp:/sbin/nologin"
volumes:
  - linkToHostFile:
      source: /etc/hosts
      destination: /etc/hosts
      readonly: true
YAML
(cd /tmp/task && jar cfM /tmp/task.zip dc-docker.yml)
code=$(curl -s -o /tmp/docker.txt -w '%{http_code}' --data-binary @/tmp/task.zip -H "api-key: $KEY" $BASE/docker/check/smokesvc)
check "docker/check with passwdEntry" "$code" 200
[ "$code" = 200 ] || { head -c 400 /tmp/docker.txt | sed 's/^/DIAG body: /'; echo; grep -E "Exception|Caused by|at com\.(payneteasy|github)" /tmp/docker.txt | grep -v "^\s*at org" | head -20 | sed 's/^/DIAG /'; }
printf '#!/bin/sh\necho "Docker version 27.0.0"\n' > /usr/local/bin/docker
code=$(curl -s -o /tmp/docker2.txt -w '%{http_code}' --data-binary @/tmp/task.zip -H "api-key: $KEY" $BASE/docker/check/smokesvc)
[ "$code" != 200 ] && grep -q "docker --version" /tmp/docker2.txt /tmp/ext-agent.log && ok "docker/check refuses a docker that is not podman" || bad "docker/check refuses a docker that is not podman ($code)"
# the failure answers the request id of its audit line
DOCKER_ID=$(sed -nE 's/^ErrorId = ([0-9a-f-]{36})$/\1/p' /tmp/docker2.txt | head -1)
DOCKER_ID=${DOCKER_ID:-no-error-id-in-the-answer}
has "the docker failure is audited with the id the caller got" /tmp/ext-agent.log "docker/check/smokesvc by static key .*: status 500, .*request $DOCKER_ID"

# --- TLS through zip-archive-version: trusted CA passes, the rogue one is a TLS refusal
mkdir -p /tmp/v && echo v1 > /tmp/v/a.txt && (cd /tmp/v && jar cfM /tmp/v1.zip a.txt)
code=$(curl -s -o /tmp/zav.txt -w '%{http_code}' --data-binary @/tmp/v1.zip -H "api-key: $KEY" $BASE/zip-archive-version/zav/v1)
check "HTTPS reload with the CA added after the build" "$code" 200
check "pointer names v1" "$(cat /opt/zav/current 2>/dev/null)" v1
code=$(curl -s -o /tmp/zav2.txt -w '%{http_code}' --data-binary @/tmp/v1.zip -H "api-key: $KEY" $BASE/zip-archive-version/zavrogue/v1)
[ "$code" != 200 ] && grep -qiE 'PKIX|certification path|SSLHandshake|unable to find valid' /tmp/ext-agent.log /tmp/zav2.txt \
  && ok "HTTPS reload with an unknown CA is a TLS refusal ($code)" || bad "HTTPS reload with an unknown CA is a TLS refusal ($code: $(head -c 200 /tmp/zav2.txt))"

# --- save-artifact, zip-dirs, fetch-url
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/app.jar -H "api-key: $KEY" $BASE/save-artifact/cpsa/1.0)
check "save-artifact" "$code" 200; test -f /opt/cp/sa/1.0.bin && ok "artifact stored" || bad "artifact stored"
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/v1.zip -H "api-key: $KEY" $BASE/zip-dirs/cpzd/a/b)
check "zip-dirs" "$code" 200; check "zip-dirs unpacked" "$(cat /opt/cp/zd/a/b/a.txt 2>/dev/null)" v1
code=$(curl -s -o /tmp/fetch.txt -w '%{http_code}' -H "api-key: $KEY" "$BASE/fetch-url/https://localhost:8443/file")
# loopback is refused by the SSRF guard (403); Jetty 12 may reject the // first (400, issue #100)
case $code in 400|403) ok "fetch-url refuses loopback ($code)" ;; *) bad "fetch-url refuses loopback ($code)" ;; esac

# --- GitLab ID tokens: a local issuer, fetch-jwks, Bearer against GITLAB_JWT rules, the audit lines
java /smoke/OidcIssuer.java 8090 /tmp/oidc > /tmp/oidc.log 2>&1 &
OIDC_PID=$!
for i in $(seq 1 120); do [ -s /tmp/oidc/old.jwt ] && break; sleep 0.5; done
$AGENT fetch-jwks smoke --issuer http://127.0.0.1:8090/git > /tmp/fetch-jwks.txt 2>&1
check "fetch-jwks writes the issuer file" "$?" 0
has "fetch-jwks names the key" /tmp/fetch-jwks.txt "1 key(s): smoke-kid"
mkdir -p /opt/oidc
printf '%s\n' '{"type":"ZIP_ARCHIVE","dir":"/opt/oidc","keys":[' \
  "{\"name\":\"ci-static\",\"type\":\"SHA256\",\"hash\":\"$HASH\"}," \
  '{"name":"gitlab","type":"GITLAB_JWT","issuer":"smoke","audience":"dc-agent:smoke",' \
  '"rules":[{"project_id":913,"ref_protected":true,"pipeline_source":["push"]}]}]}' > $CFG/oidc.json
bearer() { curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/v1.zip -H "Authorization: Bearer $(cat /tmp/oidc/$1.jwt)" $BASE/zip-archive/oidc; }
check "a GitLab token of the allowed project deploys" "$(bearer good)" 200
check "a token of another project is refused" "$(bearer other)" 401
check "a token older than maxTokenAge is refused" "$(bearer old)" 401
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/v1.zip -H "api-key: $KEY" $BASE/zip-archive/oidc)
check "the static key of the same config deploys" "$code" 200
code=$(curl -s -o /dev/null -w '%{http_code}' --data-binary @/tmp/v1.zip -H "Authorization: Bearer $(cat /tmp/oidc/good.jwt)" -H "api-key: $KEY" $BASE/zip-archive/oidc)
check "a token next to an api-key is refused" "$code" 401
has "the token authorization is logged" /tmp/ext-agent.log "Authorized command oidc by GitLab token: key gitlab, rule 0"
# the audit line: the exact size and SHA-256 of the zip sent, and the request id of the authorization line
ZIP_SHA=$(sha256sum /tmp/v1.zip | cut -d' ' -f1); ZIP_LEN=$(wc -c < /tmp/v1.zip | tr -d ' ')
has "the finished command is audited with the body sent" /tmp/ext-agent.log \
  "zip-archive/oidc by GitLab token (key gitlab, project smoke/app, ref main, job 88): status 200, body $ZIP_LEN bytes (complete) sha256 $ZIP_SHA"
AUTH_ID=$(grep -m1 "Authorized command oidc by GitLab token" /tmp/ext-agent.log | sed -E 's/.*request ([0-9a-f-]+).*/\1/')
has "the audit line carries the request id of the authorization" /tmp/ext-agent.log "status 200, body $ZIP_LEN bytes (complete) sha256 $ZIP_SHA, request $AUTH_ID"
# an authorized upload that fails: the audit line shows the error status and the id the caller got
printf 'not a zip' > /tmp/broken.zip
code=$(curl -s -o /tmp/broken.json -w '%{http_code}' --data-binary @/tmp/broken.zip -H "Authorization: Bearer $(cat /tmp/oidc/good.jwt)" $BASE/zip-archive/oidc)
check "an authorized broken archive fails" "$code" 500
ERR_ID=$(sed -nE 's/.*"errorId": *"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})".*/\1/p' /tmp/broken.json | head -1)
check "the answer of the failure carries a request id" "$(printf '%s' "$ERR_ID" | wc -c | tr -d ' ')" 36
ERR_ID=${ERR_ID:-no-error-id-in-the-answer}
has "the same id authorized the failed upload" /tmp/ext-agent.log "Authorized command oidc by GitLab token: key gitlab, rule 0.*request $ERR_ID"
has "the same id is in the error log" /tmp/ext-agent.log "$ERR_ID: Error processing /dc-agent/zip-archive/oidc"
has "the failed command is audited with its status and the id the caller got" /tmp/ext-agent.log "status 500, body 9 bytes (complete) sha256 $(sha256sum /tmp/broken.zip | cut -d' ' -f1), request $ERR_ID"
kill $OIDC_PID 2>/dev/null

# --- native: the PATH link outside the home is protected, its neighbour is not
if [ "${AGENT_KIND:-jar}" = native ]; then
  cp_post command/create/jar "{\"name\":\"onlink\",\"config\":{\"jarFilename\":\"/srv/launcher/app.jar\",\"serviceName\":\"onlink\"},$(keys)}" >/dev/null
  has "a command next to the PATH link of the agent is refused" /tmp/cp.json INVALID
  cp_post command/create/jar "{\"name\":\"nextto\",\"config\":{\"jarFilename\":\"/srv/launcher2/app.jar\",\"serviceName\":\"nextto\"},$(keys)}" >/dev/null
  has "the directory next to it is free" /tmp/cp.json CREATED
fi

if [ $fail -gt 0 ]; then
  grep -nE "Exception|Caused by|ERROR|WARN" /tmp/ext-agent.log | grep -vE "^\s*[0-9]+:\s+at " | head -30 | sed 's/^/DIAG /'
fi
kill $AGENT_PID; wait $AGENT_PID 2>/dev/null
grep -E "TLS trust store|ERROR" /tmp/ext-agent.log | head -5 | sed 's/^/LOG /'

# --- systemd-unit (the generator, native path): refuses what the sandbox cannot serve, else a drop-in
out=$($AGENT systemd-unit 2>&1); rc=$?
check "systemd-unit refuses configs the sandbox cannot serve" "$rc" 1
printf '%s\n' "$out" | grep -q "FETCH_URL needs outgoing network" && ok "systemd-unit names the fetch-url config" || bad "systemd-unit names the fetch-url config ($(printf '%s' "$out" | head -c 300))"
mkdir -p /tmp/cfg-saved /srv/www/smoke-unit && mv $CFG/* /tmp/cfg-saved/
printf '{"type":"ZIP_ARCHIVE","dir":"/srv/www/smoke-unit","apiKeys":{"%s":"ci"}}' "$HASH" > $CFG/unit.json
$AGENT systemd-unit --slice pne.slice > /tmp/paths.conf 2>/tmp/unit.err; rc=$?
check "systemd-unit prints a drop-in" "$rc" 0
has "the drop-in writes the command dir" /tmp/paths.conf "--rw /srv/www/smoke-unit"
has "the drop-in lists the service roots only" /tmp/paths.conf "--rd /service"
$AGENT systemd-unit --check /tmp/paths.conf > /tmp/unit-check.txt 2>&1; rc=$?
check "systemd-unit --check: up to date" "$rc" 0
[ "$rc" = 0 ] || sed 's/^/DIAG check: /' /tmp/unit-check.txt | head -20
$AGENT systemd-unit --host-script > /tmp/host.sh 2>/tmp/unit.err; rc=$?
check "systemd-unit --host-script" "$rc" 0
bash -n /tmp/host.sh && ok "the host script is valid bash" || bad "the host script is valid bash"
rm -f $CFG/unit.json; mv /tmp/cfg-saved/* $CFG/ 2>/dev/null

echo "RESULT pass=$pass fail=$fail skip=$skip"
test $fail -eq 0
