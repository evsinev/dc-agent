---
title: dc-operator
description: Multi-host React web console driving many agents from a git-backed config repo.
sidebar:
  order: 3
---

## Purpose

`dc-operator` is a multi-host web console. It reads a git repository of app and task
definitions and drives many agents from one place: view configuration drift, push updates,
inspect and control supervised services, and pull the config repo — all from a browser.

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
| `service/list \| view \| send-action/*` | Per-host service inventory and start/stop/etc. actions |
| `git/log \| status \| pull/*` | Inspect and `git pull` the config repo |

## Config

| Setting | Purpose |
| --- | --- |
| `REPO_DIR` | Local checkout of the config repo |
| `REPO_APPS_RELATIVE_DIR` | Apps subdirectory (default `apps`) |
| `REPO_TASKS_RELATIVE_DIR` | Tasks subdirectory (default `tasks`) |
| `CONFIG_FILE` | `operator-config.yaml` — agent hosts with their URL, task token, and control-plane token |
| `GIT_*` | Git remote / credentials for pulling the repo |
| `ASSETS_*` | Locations of the bundled UI `index.js` / `index.css` |

## Role in the system

The operator reaches each agent over two channels: the **task channel** (`api-key`, used for
`docker/push` and `docker/check`) and the **control-plane channel** (`Bearer` token, used for
service list/view/actions). See [Architecture](/dc-agent/internals/architecture/) for the channel
model.
