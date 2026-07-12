---
title: dc-operator
description: Multi-host React web console driving many agents from a git-backed config repo.
sidebar:
  order: 3
---

## Purpose

`dc-operator` is a multi-host web console. It reads a git repository of app and task
definitions and drives many agents from one place: view configuration drift, push updates,
inspect and control supervised services, view the dc-agent fleet with each agent's version and
health, and pull the config repo — all from a browser.

## Build & run

```bash
./mvnw package -Poperator-shaded
# -> dc-agent-operator/target/dc-operator.jar
java -jar dc-operator.jar
```

Serves under context path **`/dc-operator`** on port **8052** by default. The React UI
(from the separate `evsinev/dc-agent-react` repo) is bundled into the jar at build time.

## Pages

| Path | Purpose |
| --- | --- |
| `/dc-operator/` | React single-page console |
| `/dc-operator/list` | Server-rendered app list |
| `/dc-operator/app/{...}` | Server-rendered app view |
| `/dc-operator/assets/*` | Bundled UI assets (`index.js` / `index.css`) |
| `/dc-operator/health` | Liveness check |

## JSON API (`/dc-operator/api/*`)

| Path | Purpose |
| --- | --- |
| `app/list` | List apps read from `apps/*.yaml` in the repo |
| `app/view/*` | Run a `DOCKER_CHECK` against a host (drift check) |
| `app/push/*` | Run a `DOCKER_PUSH` (apply) to a host |
| `app/status/*` | Report `OK` / `DRIFT` / `ERROR` for a host |
| `agent/list` | List managed agents with their `/app-status` (version, uptime, reachability) and a service summary |
| `service/list \| view \| send-action/*` | Per-host service inventory and start/stop/etc. actions |
| `git/log \| status \| pull/*` | Inspect and `git pull` the config repo |

The `agent/list` endpoint powers the **dc-agent list** screen in the React console; it reaches
every configured agent's [`/app-status`](/dc-agent/reference/http-api/#app-status) plus its
control-plane service list and returns one combined row per agent.

## Config

| Setting | Purpose |
| --- | --- |
| `REPO_DIR` | Local checkout of the config repo |
| `REPO_APPS_RELATIVE_DIR` | Apps subdirectory (default `apps`) |
| `REPO_TASKS_RELATIVE_DIR` | Tasks subdirectory (default `tasks`) |
| `CONFIG_FILE` | `operator-config.yaml` — agent hosts (see below) |
| `GIT_*` | Git remote / credentials for pulling the repo |
| `ASSETS_*` | Locations of the bundled UI `index.js` / `index.css` |
| `APP_INSTANCE_NAME` | Instance name the operator reports on its own `/dc-operator/app-status/` (default `dc-operator`) |
| `APP_STATUS_TOKEN` | Bearer token guarding the operator's own `/app-status` endpoint |

### Agent hosts (`operator-config.yaml`)

Each managed agent is one entry under `agents`:

```yaml title="operator-config.yaml"
agents:
  - name: sandbox-1                                   # logical host id (fqsn prefix)
    url: https://sandbox-1.example.com:8051/dc-agent  # agent base URL incl. context path
    token: <agent api-key>                            # task channel — docker push/check
    cpToken: <agent CONTROL_PLANE_TOKEN>              # control-plane channel — service actions
    appStatusToken: <agent APP_STATUS_TOKEN>          # app-status channel — dc-agent list screen
```

`appStatusToken` must equal that agent's `APP_STATUS_TOKEN`; it is what the **dc-agent list**
screen (API `agent/list`) uses to read each agent's `/app-status`.

## Role in the system

The operator reaches each agent over three channels: the **task channel** (`api-key`, used for
`docker/push` and `docker/check`), the **control-plane channel** (`Bearer cpToken`, used for
service list/view/actions), and the **app-status channel** (`Bearer appStatusToken`, used by the
`agent/list` screen to read each agent's version and health). See
[Architecture](/dc-agent/internals/architecture/) for the channel model.
