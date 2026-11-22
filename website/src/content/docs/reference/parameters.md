---
title: Deploy parameters
description: Full parameter table for the war / jar / node deploy family, verified against the source.
sidebar:
  order: 1
---

<!-- Corrections vs README.md deploy table:
     - waitConnectTimeout default is 30s in code (AbstractJarServlet.java:83), README said 10s.
     - svcCommand / svstatCommand are not read: the agent runs no svc/svstat (Supervise.java). -->

These parameters apply to the [war](/dc-agent/commands/deploy-war/), [jar](/dc-agent/commands/deploy-jar/),
and [node](/dc-agent/commands/deploy-node/) commands. They go in the per-app config file
(`config/<name>.json`). Defaults below are taken from the agent source, not the README — see
the notes for where they differ.

## Parameters

| Parameter | Description | Default | Required |
| --- | --- | --- | --- |
| `warFilename` | Destination path for the uploaded WAR (`war` only); its directory must lie inside `COMMAND_DIRS` | — | `war`: yes |
| `jarFilename` | Destination path for the uploaded JAR or node ZIP (`jar`, `node`); its directory must lie inside `COMMAND_DIRS` | — | `jar`/`node`: yes |
| `serviceName` | daemontools service name; used to derive `serviceDir` and `serviceLogFile` | — | yes, unless both derived paths are set explicitly |
| `serviceDir` | daemontools service directory the agent stops and starts; must be a service directly in `SERVICES_DIR` or `SERVICES_DEFINITION_DIR` | `/service/<serviceName>` | no |
| `serviceLogFile` | Log file whose tail is returned in the response | `/var/log/<serviceName>/current` | no |
| `serviceStopTimeout` | How long the old process may take to exit after the stop request; set it above the application's own shutdown time | `30s` | no |
| `serviceStartTimeout` | How long supervise may take to start the new process | `10s` | no |
| `waitUrl` | Health URL polled after start; must return HTTP 200. If unset, the wait is skipped | — | no |
| `waitDuration` | Total time to keep polling `waitUrl` | `3m` | no |
| `waitConnectTimeout` | Connect timeout for each poll | `30s` | no |
| `waitReadTimeout` | Read timeout for each poll | `30s` | no |
| `svcCommand` | ⚠️ **Not read** — see note | *(ignored)* | no |
| `svstatCommand` | ⚠️ **Not read** — see note | *(ignored)* | no |
| `apiKeys` | `sha256:` hashes of the accepted keys → owner labels ([Configuration](/dc-agent/configuration/#the-api-key-model)) | — | yes (without it every call gets 401) |
| `type` | `JAR`, `WAR` or `NODE` — the endpoint the command is called through; another type gets 401, a missing or unknown one refuses the config (401, and the agent does not start) | — | yes |

Durations use a compact form (`30s`, `3m`, `1h`) which the agent parses as ISO-8601.

## How the service is stopped and started

The agent does what `svc` and `svstat` do, without running them: it writes one byte (`d`, `u`) to
the fifo `<serviceDir>/supervise/control` and reads `<serviceDir>/supervise/status`, which it
trusts only while supervise reads the fifo `supervise/ok`. The agent therefore needs no exec, and
a refusal is an error at once: no write permission on `control`, or no supervise (nothing reads
the fifo within 5 s). Only daemontools' own 18-byte status is read: a service under runit,
daemontools-encore or s6 (another status format) is refused before the stop request, so the deploy
fails without touching it. An agent that does not run as root needs write permission on the
`control` and `ok` fifos of the services it deploys (for example group-writable `0620`; the
`supervise` directory `0710`) — supervise keeps the modes of existing fifos.

A deploy that fails after the stop request — the old process does not exit within
`serviceStopTimeout`, the jar cannot be replaced — asks supervise to start the service again
(`u`), so a failed deploy does not leave the service down; the answer is still an error (417).
The service starts as soon as the old process has exited, with whatever jar is in place: the old
one unless it was already replaced. If supervise holds the fifo but has stopped reading it, the
stop may still arrive later: the start is queued behind it and reaches supervise after it.

One deploy per service at a time: while one runs, another deploy of the same service (by any
`jar`/`war`/`node` command) is answered 409 at once.

## Notes and corrections

The README's deploy table is inaccurate in places. The behavior below reflects the actual
code (`dc-agent-app/.../servlets/AbstractJarServlet.java`):

:::caution[`waitConnectTimeout` defaults to 30s, not 10s]
The README lists a `10s` default for `waitConnectTimeout`; the code default is **`30s`**
(`AbstractJarServlet.java:83`).
:::

:::caution[`svcCommand` / `svstatCommand` are not read]
These fields exist on the config object but are never read: the agent runs neither `svc` nor
`svstat` (see above). The former global variables `DAEMONTOOLS_SVC_PATH` and
`DAEMONTOOLS_SVSTAT_PATH` are gone too.
:::
