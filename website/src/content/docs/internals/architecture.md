---
title: Architecture
description: The modules that make up dc-agent and how they relate.
sidebar:
  order: 1
---

## Overview

dc-agent is a fleet-deployment system built on plain Jetty + servlets + Gson — no Spring, no DI
framework. A lightweight HTTP **agent** runs on every managed host. Two "director" apps push
work to those agents and supervise the services they run:

- The [dc-controller](/dc-agent/tools/dc-controller/) — a signed, auditable, single-operator path.
- The [dc-operator](/dc-agent/tools/dc-operator/) — a multi-host web console.

Internally the dependency graph is a star: every module depends only on **dc-agent-core**
(the operator also depends on the controller).

## Task vs Job

Two central concepts:

- A **task** is a zipped service-definition directory (containing `dc-docker.yml` for the docker
  flow). The agent materializes it into a daemontools service. `docker/push` applies it;
  `docker/check` is a dry-run/diff.
- A **job** wraps a task in a signed envelope (`task.zip` + `job.json` + signature + client
  cert). Jobs are used **only** by the controller flow (RSA-SHA256 signing).

## The two token channels

Each managed host is reached over two separately-tokened channels:

- **Task channel** — a token sent as the `api-key` header, used for `docker/push` and
  `docker/check` (and the other task endpoints).
- **Control-plane channel** — a token sent as `Bearer`, used for `/control-plane/api/*`
  (service list/start/stop via daemontools), gated by `CONTROL_PLANE_ENABLED`.

## Modules

### dc-agent-app — the agent
The HTTP agent that runs on each host (main class `com.payneteasy.dcagent.DcAgentApplication`).
Exposes the [task/deploy endpoints, docker endpoints, control-plane, and ui-admin API](/dc-agent/reference/http-api/)
on a raw Jetty server under context `/dc-agent`, port `8051`.

### dc-agent-core — shared library
Config models, the job/task pipeline, the docker service engine (`PushDockerAction`), the
control-plane client, and crypto/zip/yaml utilities. Every other module depends on it.

### dc-agent-cli (`dc-cli`) — job submitter
Developer's local picocli tool that builds and signs a job and POSTs it to the controller. See
[dc-cli](/dc-agent/tools/dc-cli/).

### dc-agent-controller (`dc-controller`) — job director
Ingests signed jobs, verifies them, and offers a human review page before deploying to an
agent. Context `/dc-controller`, port `8052`. See [dc-controller](/dc-agent/tools/dc-controller/).

### dc-agent-operator (`dc-operator`) — web console
Multi-host React console driven from a git-backed config repo. Context `/dc-operator`, port
`8052`. See [dc-operator](/dc-agent/tools/dc-operator/).

### dc-agent-ssh-remote-executor (`ssh-executor`) — local applier
Standalone picocli tool that runs the same docker push/check locally on a host instead of over
HTTP. See [ssh-executor](/dc-agent/tools/ssh-executor/).

### Internal / peripheral modules
- **dc-agent-upload-core** — a standalone artifact-uploader library (in a `com.acme.*`
  placeholder package). It has no main class and no other module depends on it; it is an
  orphan/experimental component, not part of the CLI→controller→agent or operator→agent flows.
- **dc-agent-ssh-cli** and **dc-agent-ssh-remote-api** — empty stubs: a `pom.xml` with no
  sources. Reserved placeholders.

## Cross-cutting notes

- **Config** is via startup parameters bound to environment variables (see
  [Installation](/dc-agent/installation/)).
- **HTTP layers differ:** the agent runs a raw Jetty server; the controller and operator run on
  an internal mini-framework.
- **Ports / contexts:** agent `/dc-agent` on `8051`; controller `/dc-controller` and operator
  `/dc-operator` both default to `8052` (not meant to run together on one host).
- **Serialization/crypto:** Gson for JSON; YAML via a snakeyaml bridge; Handlebars for docker
  templating; job envelopes are RSA-SHA256; docker artifact signatures are PGP.
