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

### Docker work directory (`TEMP_DIR`)
`docker push` / `docker check` extract the task into a fresh directory under `TEMP_DIR`
(`docker-<name>-<ms>-<random>`), created exclusively with `rwx------` — a directory someone
prepared under the same name is never reused. Before anything is extracted, the whole path of
`TEMP_DIR` is checked: every directory on the way (links included) and `TEMP_DIR` itself must be
owned by root or the agent and not writable by group/others, except a sticky directory such as
`/tmp`. Missing components are created one by one (`rwx------`), each only in a directory that
passed the same check. A `TEMP_DIR` below a directory an unprivileged user owns is rejected.

### Deleting trees
The extracted task, the `node` `app`/`target` directories and an exploded `war` are removed
without following symbolic links: a link is deleted itself, its target stays. The start must lie
strictly inside the real path of its parent directory (the temp root or the install directory),
compared by path components. On Linux the tree is deleted relative to open directory descriptors,
so a directory swapped for a link during the deletion stops it instead of redirecting it; this
relies on the install directories themselves being trusted (owned by root, not writable by
others).

### save-artifact
The `api-key` is checked before anything touches the disk. The stored file is
`<dir>/<version>.<extension>`, and the final path must lie inside the canonical `dir` (links
inside `dir` that lead out are rejected), like the ZIP guard; the file is opened without
following a link at its place. `{version}` must not contain `..`;
`replaceDirChars` may turn a substring of it into `/` for sub-directories inside `dir`. The
`x-dc-agent-file-extension` header must be a plain extension (`[A-Za-z0-9][A-Za-z0-9._-]*`,
no `..`). `dir`, `extension` and `replaceDirChars` come from server-side config.

## Control-plane channel
`/control-plane/api/*` is gated behind `CONTROL_PLANE_ENABLED` and protected by a Bearer token
(`CONTROL_PLANE_TOKEN`). The token ships with an obvious placeholder default
(`REPLACE_THIS_TEST_CONTROL_PLANE_TOKEN`) — set a real value before enabling the channel.

## Hardening checklist

- Terminate TLS in front of the agent (it speaks plain HTTP) — see [Installation](/dc-agent/installation/).
- Give every exposed endpoint a strong, random `api-key`; rotate by listing multiple keys.
- Never leave an `apiKeys` block empty or absent.
- Lock down filesystem permissions on `CONFIG_DIR`.
- Keep `TEMP_DIR` and the deploy directories on paths only root can change (the agent refuses an
  unsafe `TEMP_DIR`).
- Change `CONTROL_PLANE_TOKEN`; leave `CONTROL_PLANE_ENABLED` off unless needed.
