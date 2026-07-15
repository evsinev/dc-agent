# Utility http agent for various tasks

📖 **Full documentation: https://evsinev.github.io/dc-agent/**

dc-agent is a small HTTP agent that runs on each managed host and performs CI/CD chores on
demand. Every task is a single authenticated HTTP call, so it drops straight into a GitLab CI
or GitHub Actions job with `curl`.

## Commands

Each command is one HTTP endpoint under the context path `/dc-agent` (default port `8051`) and
is driven by a per-task config file under `./config`. See the docs for config format, the
api-key model, parameters, and full examples.

| Task                              | Endpoint                                        | Docs                                                                        |
|-----------------------------------|-------------------------------------------------|-----------------------------------------------------------------------------|
| Save a build artifact             | `POST /dc-agent/save-artifact/{name}/{version}` | [save-artifact](https://evsinev.github.io/dc-agent/commands/save-artifact/) |
| Upload a config ZIP               | `POST /dc-agent/zip-archive/{name}`             | [zip-archive](https://evsinev.github.io/dc-agent/commands/zip-archive/)     |
| Upload a ZIP into a sub-path      | `POST /dc-agent/zip-dirs/{name}/{subdir...}`    | [zip-dirs](https://evsinev.github.io/dc-agent/commands/zip-dirs/)           |
| Proxy a URL fetch                 | `GET /dc-agent/fetch-url/{targetUrl}`           | [fetch-url](https://evsinev.github.io/dc-agent/commands/fetch-url/)         |
| Deploy a WAR service              | `POST /dc-agent/war/{name}`                     | [deploy war](https://evsinev.github.io/dc-agent/commands/deploy-war/)       |
| Deploy a JAR service              | `POST /dc-agent/jar/{name}`                     | [deploy jar](https://evsinev.github.io/dc-agent/commands/deploy-jar/)       |
| Deploy a Node service             | `POST /dc-agent/node/{name}`                    | [deploy node](https://evsinev.github.io/dc-agent/commands/deploy-node/)     |
| Apply a Docker service definition | `POST /dc-agent/docker/{push,check}/{name}`     | [docker](https://evsinev.github.io/dc-agent/commands/docker/)               |

## Quick example

Store an uploaded APK on the host as `/opt/sbp-android/master-216018.apk`:

```sh
curl \
  --data-binary @app/build/outputs/apk/release/app-release.apk \
  --fail \
  -H "api-key: $UPLOAD_KEY" \
  https://dc-agent.example.com/dc-agent/save-artifact/sbp-android/$CI_COMMIT_REF_NAME-$CI_JOB_ID
```

## Build & run

```sh
./mvnw package -Pagent-shaded      # -> dc-agent-app/target/dc-agent.jar
java -jar dc-agent-app/target/dc-agent.jar
```

See [Installation](https://evsinev.github.io/dc-agent/installation/) and
[Configuration](https://evsinev.github.io/dc-agent/configuration/) for env vars, supervising
under daemontools, and a minimal working config.

## Companion tools

Beyond the agent, the project ships a multi-host web console — see the docs:
[dc-operator](https://evsinev.github.io/dc-agent/tools/dc-operator/).
Architecture and the security model are covered under
[Internals](https://evsinev.github.io/dc-agent/internals/architecture/).
