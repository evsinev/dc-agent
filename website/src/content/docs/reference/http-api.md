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
- **Bearer** — `Authorization: Bearer <CONTROL_PLANE_TOKEN>`.
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

## Control-plane endpoints

Enabled only when `CONTROL_PLANE_ENABLED=true`. Protected by a Bearer token
(`CONTROL_PLANE_TOKEN`). These accept a JSON body (`POST`; `GET` also works).

| Method | Path | Auth | Body |
| --- | --- | --- | --- |
| `POST` | `/control-plane/api/service/list` | Bearer | `ServiceListRequest` |
| `POST` | `/control-plane/api/service/view` | Bearer | `ServiceViewRequest` (`{ serviceName }`) |
| `POST` | `/control-plane/api/service/action` | Bearer | `ServiceActionRequest` (`{ serviceName, serviceAction }`) |

## UI-admin endpoints

Enabled only when `UI_ADMIN_ENABLED=true`. These have **only a CORS filter — no server-side
api-key or Bearer check is wired at the servlet layer** (see the caution in
[Security](/dc-agent/internals/security/)).

| Method | Path | Auth |
| --- | --- | --- |
| `POST` | `/ui/api/auth/token` | none (CORS) — issues a token |
| `POST` | `/ui/api/auth/refresh` | none (CORS) |
| `POST` | `/ui/api/task/list` | none (CORS) |
| `POST` | `/ui/api/task/jar/get` | none (CORS) |
| `POST` | `/ui/api/task/jar/save` | none (CORS) |
| `POST` | `/ui/api/user/info` | none (CORS) |
