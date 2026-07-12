# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, test, run

Maven multi-module project, JDK **21** required, built with the wrapper (`./mvnw`, Maven 3.6.3). Do not use a system `mvn`.

```bash
./mvnw package                       # build all modules (THIN jars only — see profiles below)
./mvnw test                          # run all tests (JUnit4 via surefire)
./mvnw --batch-mode --no-transfer-progress test   # exactly what CI (.github/workflows/maven.yml) runs
./mvnw -pl dc-agent-core test -Dtest=CreateJobServiceImplTest#signs   # single module / single test
```

- **Checkstyle runs on every build.** It is bound to the `validate` phase in the root `pom.xml` with `failsOnError=true` (config `checkstyle.xml` + `checkstyle-suppressions.xml` at repo root), so even `./mvnw package` fails fast on a style violation.
- **Runnable (fat) jars only exist under per-module shade profiles.** A plain build produces thin jars. To get a runnable artifact:

  | Profile               | Module                       | Output                                    |
  |-----------------------|------------------------------|-------------------------------------------|
  | `-Pagent-shaded`      | dc-agent-app                 | `dc-agent.jar`                            |
  | `-Pcli-shaded`        | dc-agent-cli                 | `dc-cli.jar`                              |
  | `-Pcontroller-shaded` | dc-agent-controller          | `dc-controller.jar`                       |
  | `-Poperator-shaded`   | dc-agent-operator            | `dc-operator.jar`                         |
  | `-Pexecutor-shaded`   | dc-agent-ssh-remote-executor | `dc-agent-ssh-remote-executor-shaded.jar` |

  (There is also a legacy `-Passemble-fat-jar` whose manifest `mainClass` is hard-coded to `DcAgentApplication` in app/controller/operator alike — prefer the `*-shaded` profiles.)
- **Generate the TypeScript API client** (controller & operator only): `-Pgenerate-typescript-api` → `target/api-typescript` (uses the private `api-generator-typescript-maven-plugin`, prefix `/ui/api`).

### Release
Pushing a tag matching `[0-9]*` (e.g. `1.0.9`, `1.0-5`) triggers `.github/workflows/release.yml`, which sets the Maven version from the tag (`versions:set -DnewVersion=$GITHUB_REF_NAME`), builds `-Poperator-shaded,agent-shaded`, and publishes `dc-operator-<tag>.jar` + `dc-agent-<tag>.jar` to the GitHub Release.

### Operator web UI (build-time download)
The operator's React SPA lives in a **separate repo** (`evsinev/dc-agent-react`) and is downloaded during `generate-resources` by `download-maven-plugin` (pinned via `frontend.version` + `frontend.sha256` in `dc-agent-operator/pom.xml`, unpacked to classpath `assets/`).
- Build without network / without the UI: `./mvnw package -Dfrontend.skip=true`.
- Bumping the UI requires updating **both** `frontend.version` and `frontend.sha256` (take the checksum from the published `.tar.gz.sha256` release asset, never compute it locally). The asset name/layout is a contract with that repo's release workflow.

## Architecture

A **fleet-deployment system**, plain Jetty + servlets + Gson (**no Spring, no DI framework**). A lightweight HTTP **agent** runs on every managed host; two "director" apps push service definitions to the agents and supervise the daemontools services they run.

Internal dependency graph is a star: every module depends only on **`dc-agent-core`** (operator also depends on controller). `dc-agent-upload-core` depends on nothing internal.

| Module                                            | Role                                                                                                                 | Main class                                                       |
|---------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| **dc-agent-core**                                 | shared library: config models, job/task pipeline, docker service engine, control-plane client, crypto/zip/yaml utils | —                                                                |
| **dc-agent-app**                                  | **the agent** — runs on each target host                                                                             | `com.payneteasy.dcagent.DcAgentApplication`                      |
| **dc-agent-cli** (`dc-cli`)                       | developer's local picocli tool; builds + signs a job, POSTs to controller                                            | `com.payneteasy.dcagent.cli.DcAgentCliApp`                       |
| **dc-agent-controller**                           | CLI/job-centric director (signed, auditable, single-operator)                                                        | `com.payneteasy.dcagent.controller.DcAgentControllerApplication` |
| **dc-agent-operator**                             | multi-host React web console                                                                                         | `com.payneteasy.dcagent.operator.DcAgentOperatorApplication`     |
| **dc-agent-ssh-remote-executor** (`ssh-executor`) | standalone picocli tool doing the same docker push/check as the agent, run locally on a host instead of over HTTP    | `com.payneteasy.dcagent.ssh.executor.SshExecutorApp`             |
| **dc-agent-upload-core**                          | standalone file uploader (JDK `HttpClient` + `api-key` header)                                                       | —                                                                |
| dc-agent-ssh-cli, dc-agent-ssh-remote-api         | **empty stubs** (pom only, no sources)                                                                               | —                                                                |

### Task vs Job (central concepts)
- A **task** is a zipped service-definition directory containing `dc-docker.yml`. The agent materializes it into a daemontools service. `DOCKER_PUSH` applies it; `DOCKER_CHECK` is a dry-run/diff. The engine is `dc-agent-core/.../modules/docker/PushDockerAction` (unzip → parse `dc-docker.yml` → Handlebars templating → resolve volumes → write service definition).
- A **job** wraps a task in a signed envelope (`task.zip` + `job.json` + `job-signature.json` + `client.crt`) used **only** by the controller flow (RSA-SHA256, see `core/util/RsaSigner` and `CreateJobServiceImpl`).

### Runtime data flow
Each managed host is described by a `TAgentHost` record with **two token-separated channels**:
- **task channel** — `token` sent as `api-key` header → agent `/docker/push` | `/docker/check`
- **control-plane channel** — `cpToken` sent as `Bearer` → agent `/control-plane/api/*` (service list/start/stop via daemontools; `ControlPlaneBearerFilter` gates it)

Two ways to drive agents:
1. **Controller path** (signed/auditable): `dc-cli create-job` → RSA-sign → controller `/cli/create-job` (verifies signature against an X.509 client cert from `CERTS_DIR`) → stored in `JOBS_DIR` → a human reviews at `/manage/job/{id}` → controller runs a check/deploy against the target agent.
2. **Operator path** (fleet web UI): browser → operator `/api/app/push/*` → `AppViewServiceImpl` builds the task zip and sends it to the chosen agent; service actions go through `DcAgentControlPlaneClient`. `GitServiceImpl` (jgit + jsch) pulls the config repo of tasks/apps.

### Cross-cutting patterns
- **Startup config** via the private `com.payneteasy:startup-parameters` lib: interfaces (`IStartupConfig`, `IControllerStartupConfig`, `IOperatorStartupConfig`) whose methods carry `@AStartupParameter(name="ENV_VAR", value="default")`, loaded with `StartupParametersFactory.getStartupParameters(...)`. Config values may be `classpath:` URIs (e.g. `classpath:assets/index.js`).
- **HTTP layers differ:** the agent uses raw `org.eclipse.jetty.server.Server` + the local helper `dc-agent-app/.../jetty/JettyContextRepository`. Controller & operator run on the private `com.payneteasy.mini` framework (`AppRunner.runApp`) + private `com.payneteasy.jetty.util` (`JettyServerBuilder`, `SafeHttpServlet`, `HealthServlet`). Typed JSON endpoints are registered through the private `api-servlet` `GsonJettyContextHandler.addApi(path, method, RequestClass)`.
- **Ports / context paths:** agent `/dc-agent` (**8051**); controller `/dc-controller` and operator `/dc-operator` **both default to 8052** (not meant to run on the same host together). Operator API is `/dc-operator/api/*`, assets `/dc-operator/assets/*`.
- **Web pages:** operator's `PageReactServlet` renders the Freemarker shell `dc-agent-operator/src/main/resources/templates/page-react-index.html` and injects the UI's `index.js`/`index.css` URIs; `AssetsServlet` serves those from classpath `assets/`. Server-rendered Freemarker pages also exist (operator app list/view, controller job view).
- **Serialization/crypto:** Gson everywhere (`Gsons.PRETTY_GSON`); YAML via `snakeyaml-engine` bridged through `core/yaml2json/YamlParser`; Handlebars for docker templating. Job envelopes are RSA-SHA256; artifact signature verification is **PGP** (pgpainless SOP) in `dc-agent-core/.../modules/docker/signature/PgpSignatureCheck`.

## Gotchas
- **Private Maven repo `https://maven.pne.io`** (declared in root pom) provides many `com.payneteasy.*` deps (`startup-parameters`, `api-servlet`, `mini-core`, `jetty.util`, `http-client-impl`, `os-process-impl`, `yaml2json`, `freemarker-util`, `api-generator`, …). **Build fails offline / without access to it.** Those classes are not in this repo.
- **Jetty 9.4 + `javax.servlet`** (not Jakarta) despite Java 21. `jakarta.ws.rs-api` is on the classpath but the servlet API is `javax`.
- `dc-agent-upload-core` is an **orphan** (package `com.acme.*`, not `com.payneteasy`); no module depends on it, and it declares `pgpainless-sop` but doesn't use PGP (the real PGP user is `dc-agent-core`).
- The root pom's `dependencyManagement` references a phantom artifact `dc-agent-remote-api` that no module produces (real module: `dc-agent-ssh-remote-api`).
- `TAgentHost` is **duplicated** — separate classes in `controller.service.config.model` and `operator.service.config.model` (operator's adds `cpToken`).
