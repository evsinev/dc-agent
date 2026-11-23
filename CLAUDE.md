# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, test, run

Maven multi-module project, JDK **21** required, built with the wrapper (`./mvnw`, Maven 3.9.16, script-only wrapper with `distributionSha256Sum`). Do not use a system `mvn`.

```bash
./mvnw package                       # build all modules (THIN jars only — see profiles below)
./mvnw test                          # run all tests (JUnit4 via surefire 3.5.4, pinned in the root pom)
./mvnw --batch-mode --no-transfer-progress test   # exactly what CI (.github/workflows/maven.yml) runs
./mvnw -pl dc-agent-core test -Dtest=CreateJobServiceImplTest#signs   # single module / single test
```

- **Checkstyle runs on every build.** It is bound to the `validate` phase in the root `pom.xml` with `failsOnError=true` (config `checkstyle.xml` + `checkstyle-suppressions.xml` at repo root), so even `./mvnw package` fails fast on a style violation.
- **Runnable (fat) jars only exist under per-module shade profiles.** A plain build produces thin jars. To get a runnable artifact:

  | Profile               | Module                       | Output                                    |
  |-----------------------|------------------------------|-------------------------------------------|
  | `-Pagent-shaded`      | dc-agent-app                 | `dc-agent.jar`                            |
  | `-Poperator-shaded`   | dc-agent-operator            | `dc-operator.jar`                         |

  (There is also a legacy `-Passemble-fat-jar` whose manifest `mainClass` is hard-coded to `DcAgentApplication` in app/operator alike — prefer the `*-shaded` profiles.)
- **Generate the TypeScript API client** (operator only): `-Pgenerate-typescript-api` → `target/api-typescript` (uses the private `api-generator-typescript-maven-plugin`, prefix `/ui/api`).

### Native agent (GraalVM / Mandrel)
- `native/build.sh <dc-agent.jar> <out>` — native-image in the Mandrel UBI 9 builder image pinned by digest in `native/image.sh` (glibc 2.34 → EL9+, x86_64, `-march=compatibility`); podman or docker. Build args live in the jar: `dc-agent-app/src/main/resources/META-INF/native-image/com.payneteasy/dc-agent-app/native-image.properties`.
- Metadata: `…/dc-agent-app/reachability-metadata.json` — from the tracing agent (`native/smoke/run.sh trace <jar> <dir>`, both smokes); `…/dc-agent-models/reachability-metadata.json` — every Gson model whole, **generated** by `NativeMetadataTest` (`-Dnative.metadata.write=true`); a new model without it fails that test. A new code path a native image may not see (reflection, resources, proxies) → re-trace and commit the result.
- `native/smoke/run.sh native|jar|trace <artifact>` — `agent-smoke.sh` + `extended-smoke.sh` in a disposable container (no network, read-only mounts); jar and binary must give the same results. In native, the executable is protected via `/proc/self/exe` + the `argv[0]`/`PATH` it was started by, and TLS uses the host's CA store.
- Built only on release (job `native` in `release.yml`); checked by hand on qa8 (a host with a LIVE agent — only as user `dc-agent-build`, only in containers).
- **Sandbox launcher** `native/landlock/dc-agent-landlock.c` — Landlock (all rights of the running ABI handled; `--ro/--rx/--rw` paths, `--connect-tcp` ports, ABI 6 scopes) + seccomp (TCP-only sockets, no bind/listen/UDP/unix/TFO/memfd/execveat/namespaces/io_uring) + `close_range`; it opens the listener itself (`--listen-tcp`, fd 0 → agent `WEB_SERVER_INHERITED=true`). Fail closed: exit 111, never runs the program unrestricted. `--rd DIR` lists/opens directories and reads no file (service roots, fsync parents). `native/landlock/build.sh <dir>` + `test.sh <dir>` (probe `dc-agent-landlock-probe` with a positive control; needs Landlock ABI 4+, 6 for scopes). Base unit `native/systemd/dc-agent.service` (two layers: systemd + launcher) + a drop-in generated from the configs by `dc-agent systemd-unit` (`systemd/*`: `SandboxPlanner` derives the paths per type as the servlets use them, refuses what it cannot grant safely; `--host-script` prints root's preparation — acts only on root-only paths, hands off the rest; `--check` compares with the installed drop-in, also from check-config). EL9 systemd: no `SocketBind*` (no BPF_FRAMEWORK); `NoExecPaths=/` misses `ReadWritePaths`/`PrivateTmp` mounts — repeat them in `NoExecPaths=`.

### Release
Pushing a tag matching `[0-9]*` (e.g. `1.0.9`, `1.0-5`) triggers `.github/workflows/release.yml`, which sets the Maven version from the tag (`versions:set -DnewVersion=$GITHUB_REF_NAME`), builds `-Poperator-shaded,agent-shaded`, and publishes `dc-operator-<tag>.jar` + `dc-agent-<tag>.jar` to the GitHub Release.

### Operator web UI (build-time download)
The operator's React SPA lives in a **separate repo** (`evsinev/dc-agent-react`) and is downloaded during `generate-resources` by `download-maven-plugin` (pinned via `frontend.version` + `frontend.sha256` in `dc-agent-operator/pom.xml`, unpacked to classpath `assets/`).
- Build without network / without the UI: `./mvnw package -Dfrontend.skip=true`.
- Bumping the UI requires updating **both** `frontend.version` and `frontend.sha256` (take the checksum from the published `.tar.gz.sha256` release asset, never compute it locally). The asset name/layout is a contract with that repo's release workflow.

## Architecture

A **fleet-deployment system**, plain Jetty + servlets + Gson (**no Spring, no DI framework**). A lightweight HTTP **agent** runs on every managed host; the operator web console pushes service definitions to the agents and supervises the daemontools services they run.

Internal dependency graph is a star: every module depends only on **`dc-agent-core`**.

| Module                                            | Role                                                                                                                 | Main class                                                       |
|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| **dc-agent-core**                                 | shared library: config models, task pipeline, docker service engine, control-plane client, crypto/zip/yaml utils     | —                                                                |
| **dc-agent-app**                                  | **the agent** — runs on each target host                                                                             | `com.payneteasy.dcagent.DcAgentApplication`                      |
| **dc-agent-operator**                             | multi-host React web console                                                                                         | `com.payneteasy.dcagent.operator.DcAgentOperatorApplication`     |

### Task (central concept)
- A **task** is a zipped service-definition directory containing `dc-docker.yml`. The agent materializes it into a daemontools service. `DOCKER_PUSH` applies it; `DOCKER_CHECK` is a dry-run/diff. The engine is `dc-agent-core/.../modules/docker/PushDockerAction` (unzip → read bound variables → Handlebars templating → parse `dc-docker.yml` → config checks → resolve volumes → write service definition). When a volume declares `owner`/`mode` (hands a host dir to a non-root container, `securityContext.runAsUser`), `preflight/WritePathPreflight` first checks every path the root agent writes — before any disk change, same for CHECK and PUSH; `website/.../commands/docker.mdx` documents the rules.

### Command configs (agent)
- Always in **`/opt/dc-agent/config`** (`DcAgentApplication.CONFIG_DIR`, not a parameter; `CONFIG_DIR` set elsewhere refuses the start). Tests pass another dir via `start(config, dir)`.
- One rule set in `dc-agent-core/.../config/validation/` — `CommandConfigFile` (regular file, UTF-8, ≤ 1 MiB, duplicate names refused, `apiKeys` names masked in every message), `CommandConfigValidator` (known `type` required — never inferred, `apiKeys`, typed parse, `ZipArchiveVersionSettings`, paths) — applied at start (`ConfigDirAudit`, refuses to start), in `check-config`, before the control plane writes (`INVALID`) and on every request (`ConfigServiceImpl`, uniform 401). `ConfigCheckMode` differs only in plaintext keys.
- Keys are stored as `sha256:<hex>` (`ApiKeyHash`); a plaintext key never authorizes. At start `ApiKeyMigration` hashes plaintext keys (atomic `AtomicFiles` writes); `java -jar dc-agent.jar hash-key` reads a key from stdin.
- **`keys`** (instead of the legacy `apiKeys`, never both): typed entries with a strict schema (`CommandKeys`) — `SHA256` (a static key, `api-key`/Basic) and `GITLAB_JWT` (a GitLab CI ID token, `Authorization: Bearer`; `core/auth/gitlab`: `GitlabTokenVerifier` RS256 + iss/aud/time/maxTokenAge, `GitlabRules` — `project_id` required in every rule, claims compared only as JSON strings). The header decides the kind. Issuer files `/opt/dc-agent/keys/<issuer>.json` (pinned `iss` + JWKS), read on every call, written only by `dc-agent fetch-jwks` (root, outside the sandbox); `migrate-keys` moves `apiKeys` to `keys`. The control plane never changes a config with `keys`. `AuditFilter` logs every authorized command (key, status, body SHA-256, request id).
- Paths (`CommandPathPolicy`): a command's `dir` / jar directory must lie inside `COMMAND_DIRS` (`/opt,/srv`) and away from the agent and daemontools services (every symlink hop counted, `PathResolver`).
- Never put a real key, a config secret or a path of CONFIG_DIR into a message that leaves the agent; test fixtures store keys via `TestConfigs.hash(...)`.

### Runtime data flow
Each managed host is described by a `TAgentHost` record with **two token-separated channels**:
- **task channel** — `token` sent as `api-key` header → agent `/docker/push` | `/docker/check`
- **control-plane channel** — `cpToken` sent as `Bearer` → agent `/control-plane/api/*` (service list/start/stop via daemontools; `ControlPlaneBearerFilter` gates it)

Driving agents (**operator path**, fleet web UI): browser → operator `/api/app/push/*` → `AppViewServiceImpl` builds the task zip and sends it to the chosen agent; service actions go through `DcAgentControlPlaneClient`. `GitServiceImpl` (jgit + jsch) pulls the config repo of tasks/apps.

### Cross-cutting patterns
- **Startup config** via the private `com.payneteasy:startup-parameters` lib: interfaces (`IStartupConfig`, `IOperatorStartupConfig`) whose methods carry `@AStartupParameter(name="ENV_VAR", value="default")`, loaded with `StartupParametersFactory.getStartupParameters(...)`. Config values may be `classpath:` URIs (e.g. `classpath:assets/index.js`).
- **HTTP layers differ:** the agent uses raw `org.eclipse.jetty.server.Server` + the local helper `dc-agent-app/.../jetty/JettyContextRepository`. The operator runs on the private `com.payneteasy.mini` framework (`AppRunner.runApp`) + private `com.payneteasy.jetty.util` (`JettyServerBuilder`, `SafeHttpServlet`, `HealthServlet`). Typed JSON endpoints are registered through the private `api-servlet` `GsonJettyContextHandler.addApi(path, method, RequestClass)`.
- **Ports / context paths:** agent `/dc-agent` (**8051**); operator `/dc-operator` (**8052**). Operator API is `/dc-operator/api/*`, assets `/dc-operator/assets/*`.
- **Web pages:** operator's `PageReactServlet` renders the Freemarker shell `dc-agent-operator/src/main/resources/templates/page-react-index.html` and injects the UI's `index.js`/`index.css` URIs; `AssetsServlet` serves those from classpath `assets/`. Server-rendered Freemarker pages also exist (operator app list/view).
- **Serialization/crypto:** Gson everywhere (`Gsons.PRETTY_GSON`); YAML via `snakeyaml-engine` bridged through `core/yaml2json/YamlParser`; Handlebars for docker templating. Artifact signature verification is **PGP** (pgpainless SOP) in `dc-agent-core/.../modules/docker/signature/PgpSignatureCheck`.

## Gotchas
- **Private Maven repo `https://maven.pne.io`** (declared in root pom) provides many `com.payneteasy.*` deps (`startup-parameters`, `api-servlet`, `mini-core`, `jetty.util`, `http-client-impl`, `yaml2json`, `freemarker-util`, `api-generator`, …). **Build fails offline / without access to it.** Those classes are not in this repo.
- **Jetty 12.1 `ee8` + `javax.servlet`** (not Jakarta) despite Java 21 (`jetty.version` in the root pom; servlet API is `jetty-servlet-api` 4.0 via `jetty-ee8-servlet`). `jakarta.ws.rs-api` is on the classpath but the servlet API is `javax`.
