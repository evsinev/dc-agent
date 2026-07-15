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
  | `-Poperator-shaded`   | dc-agent-operator            | `dc-operator.jar`                         |

  (There is also a legacy `-Passemble-fat-jar` whose manifest `mainClass` is hard-coded to `DcAgentApplication` in app/operator alike — prefer the `*-shaded` profiles.)
- **Generate the TypeScript API client** (operator only): `-Pgenerate-typescript-api` → `target/api-typescript` (uses the private `api-generator-typescript-maven-plugin`, prefix `/ui/api`).

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
- A **task** is a zipped service-definition directory containing `dc-docker.yml`. The agent materializes it into a daemontools service. `DOCKER_PUSH` applies it; `DOCKER_CHECK` is a dry-run/diff. The engine is `dc-agent-core/.../modules/docker/PushDockerAction` (unzip → parse `dc-docker.yml` → Handlebars templating → resolve volumes → write service definition).

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
- **Private Maven repo `https://maven.pne.io`** (declared in root pom) provides many `com.payneteasy.*` deps (`startup-parameters`, `api-servlet`, `mini-core`, `jetty.util`, `http-client-impl`, `os-process-impl`, `yaml2json`, `freemarker-util`, `api-generator`, …). **Build fails offline / without access to it.** Those classes are not in this repo.
- **Jetty 9.4 + `javax.servlet`** (not Jakarta) despite Java 21. `jakarta.ws.rs-api` is on the classpath but the servlet API is `javax`.
