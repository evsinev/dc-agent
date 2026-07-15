---
title: Architecture
description: The modules that make up dc-agent and how they relate.
sidebar:
  order: 1
---

## Overview

dc-agent is a fleet-deployment system built on plain Jetty + servlets + Gson — no Spring, no DI
framework. A lightweight HTTP **agent** runs on every managed host. The
[dc-operator](/dc-agent/tools/dc-operator/) — a multi-host web console — pushes work to those
agents and supervises the services they run.

Internally the dependency graph is a star: every module depends only on **dc-agent-core**.

## Tasks

A **task** is a zipped service-definition directory (containing `dc-docker.yml` for the docker
flow). The agent materializes it into a daemontools service. `docker/push` applies it;
`docker/check` is a dry-run/diff.

## The token channels

Each managed host is reached over separately-tokened channels:

- **Task channel** — a token sent as the `api-key` header, used for `docker/push` and
  `docker/check` (and the other task endpoints).
- **Control-plane channel** — a token sent as `Bearer`, used for `/control-plane/api/*`
  (service list/start/stop via daemontools), gated by `CONTROL_PLANE_ENABLED`.
- **App-status channel** — a `Bearer` token (`APP_STATUS_TOKEN`), used for the always-on
  [`/app-status`](/dc-agent/reference/http-api/#app-status) endpoint that reports the instance's
  version and health. In `operator-config.yaml` this is the per-host `appStatusToken`.

## Modules

### dc-agent-app — the agent
The HTTP agent that runs on each host (main class `com.payneteasy.dcagent.DcAgentApplication`).
Exposes the [task/deploy, docker, app-status, and control-plane endpoints](/dc-agent/reference/http-api/)
on a raw Jetty server under context `/dc-agent`, port `8051`.

### dc-agent-core — shared library
Config models, the job/task pipeline, the docker service engine (`PushDockerAction`), the
control-plane client, and crypto/zip/yaml utilities. Every other module depends on it.

### dc-agent-operator (`dc-operator`) — web console
Multi-host React console driven from a git-backed config repo. Context `/dc-operator`, port
`8052`. See [dc-operator](/dc-agent/tools/dc-operator/).

## Cross-cutting notes

- **Config** is via startup parameters bound to environment variables (see
  [Installation](/dc-agent/installation/)).
- **HTTP layers differ:** the agent runs a raw Jetty server; the operator runs on an internal
  mini-framework.
- **Ports / contexts:** agent `/dc-agent` on `8051`; operator `/dc-operator` on `8052`.
- **Serialization/crypto:** Gson for JSON; YAML via a snakeyaml bridge; Handlebars for docker
  templating; docker artifact signatures are PGP.
