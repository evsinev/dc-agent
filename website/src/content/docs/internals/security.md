---
title: Security
description: The api-key model, path handling, and auth caveats — as implemented in the code.
sidebar:
  order: 2
---

This page documents the agent's security behavior **as implemented**, including a few sharp
edges worth knowing before you expose an agent.

## The api-key model

Task and deploy endpoints authenticate with `CheckApiKey`:

- The caller sends the secret in the **`api-key`** header, or as the **password** part of an
  HTTP Basic `Authorization` header (the agent decodes `user:password` and takes the part after
  the `:`).
- The value must be a **key** of the endpoint's `apiKeys` map. The map value is only a label and
  is never matched.
- Keys are stored **in plaintext** in the config files. There is no hashing and no issuance
  mechanism — keys are written into the config by hand. Anyone who can read `config/*.json` can
  read every live secret, so restrict permissions on `CONFIG_DIR`.

:::danger[Missing `apiKeys` disables authentication]
If a config file has no `apiKeys` object, the endpoint accepts **any** request — the check logs
a warning and passes. Never deploy an endpoint config without an `apiKeys` block.
:::

## Path handling

### ZIP extraction (Zip Slip guard)
`zip-archive`, `zip-dirs`, and the `node` deploy all extract archives through a guarded writer
(`SafeFiles.createFileGuarded`). It canonicalizes the target directory and each entry's
destination and requires the destination to stay inside the target directory; a `../` entry or
an absolute-path entry is rejected with a `SecurityException`. The `zip-dirs` URL sub-path is
additionally restricted to a strict character whitelist (`0-9 a-z A-Z . - _`).

### save-artifact
`save-artifact` rejects a `{version}` segment containing `..`. Two things to be aware of:

- The check covers the `{version}` segment only; the `{name}` segment (used to pick the config
  file) is not checked.
- The final path is built by joining the configured `dir` with the file name, **without** a
  canonical-containment guard like the ZIP path has. The `replaceDirChars` feature can also turn
  a substring of `{version}` into `/`, deliberately allowing sub-directories. Because `dir` and
  `extension` come from server-side config, exploitability is limited — but treat `dir` and
  `replaceDirChars` as trusted settings.

## Control-plane channel
`/control-plane/api/*` is gated behind `CONTROL_PLANE_ENABLED` and protected by a Bearer token
(`CONTROL_PLANE_TOKEN`). The token ships with an obvious placeholder default
(`REPLACE_THIS_TEST_CONTROL_PLANE_TOKEN`) — set a real value before enabling the channel.

## UI-admin channel

:::caution[`/ui/api/*` has no servlet-level auth]
The `/ui/api/*` admin endpoints (enabled by `UI_ADMIN_ENABLED`) are fronted only by a CORS
filter — no api-key or Bearer filter is wired at the servlet layer. Keep `UI_ADMIN_ENABLED` off
unless you understand and have compensated for this (e.g. network isolation), and never expose
these endpoints directly to the internet.
:::

## Hardening checklist

- Terminate TLS in front of the agent (it speaks plain HTTP) — see [Installation](/dc-agent/installation/).
- Give every exposed endpoint a strong, random `api-key`; rotate by listing multiple keys.
- Never leave an `apiKeys` block empty or absent.
- Lock down filesystem permissions on `CONFIG_DIR`.
- Change `CONTROL_PLANE_TOKEN`; leave `CONTROL_PLANE_ENABLED` / `UI_ADMIN_ENABLED` off unless needed.
