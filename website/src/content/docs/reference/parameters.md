---
title: Deploy parameters
description: Full parameter table for the war / jar / node deploy family, verified against the source.
sidebar:
  order: 1
---

<!-- Corrections vs README.md deploy table:
     - waitConnectTimeout default is 30s in code (AbstractJarServlet.java:83), README said 10s.
     - serviceStartTimeout has no effect: start uses getServiceStopTimeout() with a "10s"
       fallback (AbstractJarServlet.java:79). README lists 10s as if the key worked.
     - svcCommand / svstatCommand are not read from config; the effective paths come from the
       global env vars DAEMONTOOLS_SVC_PATH / DAEMONTOOLS_SVSTAT_PATH (IStartupConfig.java:42-46). -->

These parameters apply to the [war](/dc-agent/commands/deploy-war/), [jar](/dc-agent/commands/deploy-jar/),
and [node](/dc-agent/commands/deploy-node/) commands. They go in the per-app config file
(`config/<name>.json`). Defaults below are taken from the agent source, not the README — see
the notes for where they differ.

## Parameters

| Parameter | Description | Default | Required |
| --- | --- | --- | --- |
| `warFilename` | Destination path for the uploaded WAR (`war` only) | — | `war`: yes |
| `jarFilename` | Destination path for the uploaded JAR or node ZIP (`jar`, `node`) | — | `jar`/`node`: yes |
| `serviceName` | daemontools service name; used to derive `serviceDir` and `serviceLogFile` | — | yes, unless both derived paths are set explicitly |
| `serviceDir` | daemontools service directory passed to `svc` | `/service/<serviceName>` | no |
| `serviceLogFile` | Log file whose tail is returned in the response | `/var/log/<serviceName>/current` | no |
| `serviceStopTimeout` | Timeout for stopping the service. **Also used as the start timeout** (see note) | `30s` | no |
| `serviceStartTimeout` | ⚠️ **No effect** — see note | *(ignored)* | no |
| `waitUrl` | Health URL polled after start; must return HTTP 200. If unset, the wait is skipped | — | no |
| `waitDuration` | Total time to keep polling `waitUrl` | `3m` | no |
| `waitConnectTimeout` | Connect timeout for each poll | `30s` | no |
| `waitReadTimeout` | Read timeout for each poll | `30s` | no |
| `svcCommand` | ⚠️ **Not read** from config — see note | *(ignored)* | no |
| `svstatCommand` | ⚠️ **Not read** from config — see note | *(ignored)* | no |
| `apiKeys` | Accepted secrets → owner labels | — | yes (else open) |

Durations use a compact form (`30s`, `3m`, `1h`) which the agent parses as ISO-8601.

## Notes and corrections

The README's deploy table is inaccurate in three places. The behavior below reflects the actual
code (`dc-agent-app/.../servlets/AbstractJarServlet.java`, `IStartupConfig.java`):

:::caution[`serviceStartTimeout` is ignored]
The documented `serviceStartTimeout` key has **no effect**. When starting the service the agent
passes `serviceStopTimeout` (falling back to `10s` if that too is unset). Consequences: if you
set `serviceStopTimeout`, the same value is used for both stop and start; the start timeout is
`10s` only when `serviceStopTimeout` is unset; and there is no way to set the start timeout
independently. (`AbstractJarServlet.java:79`.)
:::

:::caution[`waitConnectTimeout` defaults to 30s, not 10s]
The README lists a `10s` default for `waitConnectTimeout`; the code default is **`30s`**
(`AbstractJarServlet.java:83`).
:::

:::caution[`svcCommand` / `svstatCommand` are not per-app config]
These fields exist on the config object but are never read. The paths to the `svc` and `svstat`
binaries come from the global environment variables `DAEMONTOOLS_SVC_PATH` (default
`/usr/bin/svc`) and `DAEMONTOOLS_SVSTAT_PATH` (default `/usr/bin/svstat`)
(`IStartupConfig.java:42-46`). The default values match the README, but you can only override
them globally, not per app.
:::
