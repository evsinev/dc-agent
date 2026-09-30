---
title: Security
description: The api-key model, path handling, and auth caveats — as implemented in the code.
sidebar:
  order: 2
---

This page documents the agent's security behavior **as implemented**, including a few sharp
edges worth knowing before you expose an agent.

## The api-key model

Every task and deploy endpoint (`zip-archive`, `zip-dirs`, `save-artifact`, `fetch-url`, `jar`,
`war`, `node`, `docker/push`, `docker/check`) runs one authorization step (`CommandAuth`) **before
anything else** — before reading the request body, creating directories, building paths from the
config or logging the request:

1. The request carries a key: the **`api-key`** header, or the **password** of an HTTP
   `Authorization: Basic` header (the agent decodes `user:password` and takes everything after the
   first `:`, so the password may contain `:`). Other schemes (`Bearer`, …) and malformed Base64
   count as no key. No key — the config is not even read.
2. The command name from the URL is valid: `0-9 a-z A-Z . _ -`, no `..` — the same rule the
   operator applies when it creates a command.
3. The config `<name>.json` / `<name>.yml` loads. A missing, empty or broken file is refused.
4. The config `type` matches the endpoint (`ZIP_ARCHIVE`, `ZIP_DIRS`, `SAVE_ARTIFACT`,
   `FETCH_URL`, `JAR`, `WAR`, `NODE`, `DOCKER`). Keys belong to a config name, and all configs
   share one `CONFIG_DIR`, so without this a `save-artifact` key would unpack an arbitrary archive
   via `/zip-archive/` into the same `dir`. A config **without** `type` (older, hand-written) is
   accepted with a warning in the agent log; so is an unknown value — a typo like `save-artifact`
   reads as no `type`, so the warning is the only sign of it.
5. The key is one of the config's `apiKeys`. The map value is only a label and is never matched.

Every refusal is the same **401** with the message `Unauthorized` — an unknown command, a broken
config and a wrong key look identical from outside, and no path of `CONFIG_DIR` leaves the agent.
The reason (step, command name, the loader's exception type) goes to the agent log only; keys and
loader exception messages are never logged.

- Keys are stored **in plaintext** in the config files. There is no hashing and no issuance
  mechanism — keys are written into the config by hand. Anyone who can read `config/*.json` can
  read every live secret, so restrict permissions on `CONFIG_DIR`.
- A config without `apiKeys` (or with an empty map) refuses every request.

## Path handling

### ZIP extraction (Zip Slip guard)
`zip-archive`, `zip-dirs`, and the `node` deploy all extract archives through a guarded writer
(`SafeFiles.createFileGuarded`). It canonicalizes the target directory and each entry's
destination and requires the destination to stay inside the target directory; a `../` entry or
an absolute-path entry is rejected with a `SecurityException`. The `zip-dirs` URL sub-path is
additionally restricted to a strict character whitelist (`0-9 a-z A-Z . - _`), the segments `.`
and `..` are refused, and the resolved directory must stay inside the canonical `dir` (a link
inside `dir` that leads out is rejected); it is created only after authorization.

### Docker work directory (`TEMP_DIR`)
`docker push` / `docker check` extract the task into a fresh directory under `TEMP_DIR`
(`docker-<name>-<ms>-<random>`), created exclusively with `rwx------` — a directory someone
prepared under the same name is never reused. Before anything is extracted, the whole path of
`TEMP_DIR` is checked: every directory on the way (links included) and `TEMP_DIR` itself must be
owned by root or the agent and not writable by group/others, except a sticky directory such as
`/tmp`. Missing components are created one by one (`rwx------`), each only in a directory that
passed the same check. A `TEMP_DIR` below a directory an unprivileged user owns is rejected.

### Container `/etc/passwd`
`securityContext.passwdEntry` never mounts the host's `/etc/passwd` (it would expose every host
account inside the container). With the docker runtime the agent writes a small
`container-passwd` (root + the rendered entry) into the service directory, which must be trusted
like `run` — owned by root, not writable by others (checked before each write and delete); the file
is opened without following a link and gets mode `0644`. Someone who can write to the service
directory can already change `run`, which runs as root.

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

The guard catches links that are already in place; it cannot stop someone who can change `dir` or
its sub-directories from swapping one for a link during an upload. Keep `dir` and everything below
it writable only by root (the agent).

## Control-plane channel
`/control-plane/api/*` is gated behind `CONTROL_PLANE_ENABLED` and protected by a Bearer token
(`CONTROL_PLANE_TOKEN`). Whoever holds the token can create commands with their own api keys and
then deploy through them, so with `CONTROL_PLANE_ENABLED=true` the agent **refuses to start** if
`CONTROL_PLANE_TOKEN` is empty or still the published placeholder
(`REPLACE_THIS_TEST_CONTROL_PLANE_TOKEN`). With the control plane off, the token is not checked.

## Hardening checklist

- Terminate TLS in front of the agent (it speaks plain HTTP) — see [Installation](/dc-agent/installation/).
- Give every exposed endpoint a strong, random `api-key`; rotate by listing multiple keys.
- Give every config its `type` (the operator does it for you); check the agent log for
  "has no type" warnings.
- Lock down filesystem permissions on `CONFIG_DIR`.
- After upgrading the agent, run `DOCKER_CHECK` for every service: unknown keys in `dc-docker.yml`
  (typos that used to be ignored, e.g. in `securityContext`) now fail the push.
- Keep `TEMP_DIR`, the deploy directories and every `save-artifact` `dir` on paths only root can
  change (the agent refuses an unsafe `TEMP_DIR`).
- Set your own `CONTROL_PLANE_TOKEN` (required with `CONTROL_PLANE_ENABLED=true`); leave the
  control plane off unless needed.
