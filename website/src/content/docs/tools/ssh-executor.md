---
title: ssh-executor
description: Standalone tool that applies or checks a Docker service definition locally on a host.
sidebar:
  order: 4
---

## Purpose

`ssh-executor` (module `dc-agent-ssh-remote-executor`) is a standalone picocli tool that
performs the same Docker `push`/`check` as the agent's [docker endpoints](/dc-agent/commands/docker/),
but **locally on a host** — run over SSH instead of over the agent's HTTP API. It uses the same
underlying engine, so the result is identical; it just skips the HTTP layer.

## Build

```bash
./mvnw package -Pexecutor-shaded
# -> dc-agent-ssh-remote-executor/target/dc-agent-ssh-remote-executor-shaded.jar
```

## Usage

```bash title="Check then push a service definition"
java -jar dc-agent-ssh-remote-executor-shaded.jar check -zf myservice.zip
java -jar dc-agent-ssh-remote-executor-shaded.jar push  -zf myservice.zip
```

The first positional argument is the action — `push` (apply) or `check` (dry-run). The service
name is derived from the ZIP file name.

| Option | Description | Default |
| --- | --- | --- |
| `-zf`, `--zip-file` | Service-definition ZIP to apply/check | — (required) |
| `-td`, `--temp-dir` | Scratch directory | `/tmp/dc-agent` |
| `-sd`, `--service-definition-dir` | Where service definitions are written | `/etc/service.d` |
| `-ld`, `--service-log-dir` | Log directory root | `/var/log` |

## Role in the system

`push` writes the service files to disk; `check` computes the diff without writing. It is the
local/SSH alternative to calling an agent's `/docker/push` or `/docker/check` — handy for
bootstrapping a host or applying a definition where the HTTP agent isn't reachable.
