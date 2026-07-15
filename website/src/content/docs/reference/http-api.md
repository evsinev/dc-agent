---
title: HTTP API
description: Every dc-agent endpoint — method, path, authentication, and request body.
sidebar:
  order: 2
---

All agent endpoints are served under the context path (default `/dc-agent`) on port `8051`,
e.g. `http://host:8051/dc-agent/health`. Authentication is one of:

- **api-key** — the `CheckApiKey` mechanism: header `api-key`, or the password part of an HTTP
  Basic `Authorization` header, matched against the endpoint's `apiKeys` map. See
  [Security](/dc-agent/internals/security/).
- **Bearer (control-plane)** — `Authorization: Bearer <CONTROL_PLANE_TOKEN>`, for `/control-plane/api/*`.
- **Bearer (app-status)** — `Authorization: Bearer <APP_STATUS_TOKEN>`, for `/app-status` (a **separate** token).
- **none / CORS** — no server-side auth check.

## Task & deploy endpoints

| Method | Path | Auth | Body | Config file |
| --- | --- | --- | --- | --- |
| `POST` | `/save-artifact/{name}/{version}` | api-key | raw file bytes | `config/{name}.json` |
| `POST` | `/zip-archive/{name}` | api-key | ZIP | `config/{name}.json` |
| `POST` | `/zip-dirs/{name}/{subdir...}` | api-key | ZIP | `config/{name}.json` |
| `GET` | `/fetch-url/{targetUrl}` | api-key | — | `config/fetch-url.json` (fixed) |
| `POST` | `/jar/{name}` | api-key | JAR | `config/{name}.json` |
| `POST` | `/war/{name}` | api-key | WAR | `config/{name}.json` |
| `POST` | `/node/{name}` | api-key | ZIP bundle | `config/{name}.json` |
| `POST` | `/docker/push/{name}` | api-key | task ZIP | `config/dc-docker.json` (fixed) |
| `POST` | `/docker/check/{name}` | api-key | task ZIP | `config/dc-docker.json` (fixed) |
| `GET` | `/health` | none | — | — |

`{name}` selects the config file **except** for `fetch-url` and the two `docker` endpoints,
which use a fixed config file (there `{name}` is the target/service name).

## App status

Always enabled. Protected by a Bearer token (`APP_STATUS_TOKEN`) — a **separate** token from the
control-plane one. Reports this instance's build version and identity as JSON.

| Method | Path | Auth | Body |
| --- | --- | --- | --- |
| `GET` | `/app-status/` | Bearer (`APP_STATUS_TOKEN`) | — |
| `GET` | `/app-status/match-all/host/{h}/instance/{i}/port/{p}` | Bearer (`APP_STATUS_TOKEN`) | — |

The response JSON carries `type`, `appInstanceName`, `appVersion`, `hostname`, `port`,
`responseId`, `responseEpoch`, and `uptimeMs`. `appVersion` comes from the runnable jar's
manifest `Implementation-Version`, so a non-release build reports `error-no-impl-version-…`.
A wrong or missing token returns **401**. The optional `match-all/...` form asserts the running
host/instance/port equals the supplied values and returns **412** on mismatch, **200** on match —
useful as a load-balancer / deploy sanity check.

## Control-plane endpoints

Enabled only when `CONTROL_PLANE_ENABLED=true`. Protected by a Bearer token
(`CONTROL_PLANE_TOKEN`). These accept a JSON body (`POST`; `GET` also works).

| Method | Path | Auth | Body |
| --- | --- | --- | --- |
| `POST` | `/control-plane/api/service/list` | Bearer | `ServiceListRequest` |
| `POST` | `/control-plane/api/service/view` | Bearer | `ServiceViewRequest` (`{ serviceName }`) |
| `POST` | `/control-plane/api/service/action` | Bearer | `ServiceActionRequest` (`{ serviceName, serviceAction }`) |
