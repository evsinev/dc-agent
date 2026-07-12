---
title: dc-controller
description: Director app that ingests signed jobs and gives a human a review-and-deploy page.
sidebar:
  order: 2
---

## Purpose

`dc-controller` is a "director" web app that sits between [dc-cli](/dc-agent/tools/dc-cli/) and the agents. It
ingests signed jobs, verifies their signatures, stores them, and presents a human with a review
page before anything is applied to a target agent. This is the signed, auditable,
single-operator deployment path.

## Build & run

```bash
./mvnw package -Pcontroller-shaded
# -> dc-agent-controller/target/dc-controller.jar
java -jar dc-controller.jar
```

Serves under context path **`/dc-controller`** on port **8052** by default.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/dc-controller/health` | Liveness check |
| `POST` | `/dc-controller/cli/create-job` | Receive a signed job from `dc-cli`; verify signature against the operator cert in `CERTS_DIR`, store the job in `JOBS_DIR`, return `{ jobUrl }` |
| `GET` | `/dc-controller/manage/job/{id}` | Human review page; re-runs a `DOCKER_CHECK` against the target agent to show current status/drift |

## Config

| Setting | Purpose |
| --- | --- |
| `JOBS_DIR` | Directory where received job envelopes are stored |
| `CERTS_DIR` | Trusted operator certificates; a job's `consumerKey` maps to `{consumerKey}.crt` |
| `MANAGE_BASE_URL` | Base URL used to build the returned review link |
| `CONFIG_FILE` | `controller-config.yaml` — the agent hosts (URL + token) the controller can target |

## Role in the system

`dc-cli` signs and submits → the controller verifies and stores → a human opens
`/manage/job/{id}` and reviews the check result → the controller applies the job to the target
agent over its task channel. Contrast with [dc-operator](/dc-agent/tools/dc-operator/), which is the
multi-host web console path.
