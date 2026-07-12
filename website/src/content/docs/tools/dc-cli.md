---
title: dc-cli
description: Developer CLI that packages, signs, and submits a deployment job to the controller.
sidebar:
  order: 1
---

## Purpose

`dc-cli` is a developer's local command-line tool (built with picocli). It packages a task
directory, wraps it in an RSA-signed job envelope, and submits it to the
[dc-controller](/dc-agent/tools/dc-controller/) for human review — the signed, auditable deployment path
(as opposed to hitting an agent directly with `curl`).

## Build

```bash
./mvnw package -Pcli-shaded
# -> dc-agent-cli/target/dc-cli.jar
```

## Usage

```bash title="Submit a job"
java -jar dc-cli.jar create-job \
  --task   myservice \
  --type   DOCKER_PUSH \
  --host   host-01.example.com
```

- `--base-dir` (global, default `.`) — root under which `config/` and the task directory live.
- `create-job` is currently the only subcommand.

| Option | Description |
| --- | --- |
| `--task` | Name of the task directory to package |
| `--type` | Task type — one of `ZIP_ARCHIVE`, `FETCH_URL`, `SAVE_ARTIFACT`, `JAR`, `WAR`, `NODE`, `SERVICE`, `DOCKER`, `DOCKER_CHECK`, `DOCKER_PUSH` |
| `--host` | Target agent host the job is destined for |

## Config

`create-job` reads `<base-dir>/config/config.yaml` plus a set of certificate/key files
(resolved relative to `<base-dir>/config/`):

| Field | Description | Default |
| --- | --- | --- |
| `baseUrl` | Controller base URL to POST the job to | — |
| `consumerKey` | Identifier of this operator; matches a cert on the controller | — |
| `caCertPath` | CA certificate | `ca.crt` |
| `clientCertPath` | Client (operator) certificate | `client.crt` |
| `clientPrivateKeyPath` | Client private key used to sign the job | `client.key` |
| `openUrlCommand` | Command used to open the review URL after submit | `open` |
| `openUrlCommandArgs` | Arguments for that command | `$url` |

## What it does

1. Reads the CLI config and zips the task directory.
2. Builds a signed job envelope (`task.zip` + `job.json` + signature + client cert).
3. `POST`s it to the controller's `/cli/create-job`.
4. Opens the returned job-review URL (`/manage/job/{id}`) in a browser.

The controller verifies the signature against the operator's X.509 certificate before storing
the job for review. See [dc-controller](/dc-agent/tools/dc-controller/) for the receiving end.
