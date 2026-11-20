---
title: Security
description: The api-key model, path handling, and auth caveats — as implemented in the code.
sidebar:
  order: 2
---

This page documents the agent's security behavior **as implemented**, including a few sharp
edges worth knowing before you expose an agent.

## The api-key model

Every task and deploy endpoint (`zip-archive`, `zip-dirs`, `zip-archive-version`, `save-artifact`,
`fetch-url`, `jar`, `war`, `node`, `docker/push`, `docker/check`) runs one authorization step (`CommandAuth`) **before
anything else** — before reading the request body, creating directories, building paths from the
config or logging the request:

1. The request carries a key: the **`api-key`** header, or the **password** of an HTTP
   `Authorization: Basic` header (the agent decodes `user:password` and takes everything after the
   first `:`, so the password may contain `:`). Other schemes (`Bearer`, …) and malformed Base64
   count as no key. No key — the config is not even read.
2. The command name from the URL is valid: `0-9 a-z A-Z . _ -`, no `..` — the same rule the
   operator applies when it creates a command.
3. The config `<name>.json` / `<name>.yml` loads and passes the [same checks as at
   start](#what-the-agent-checks-at-start): a regular file (not a link), not both `.json` and `.yml`,
   no repeated names, a known `type`, `apiKeys`, its paths. A config may be added or changed while
   the agent runs, so the checks run on every call.
4. The config `type` matches the endpoint (`ZIP_ARCHIVE`, `ZIP_DIRS`, `ZIP_ARCHIVE_VERSION`,
   `SAVE_ARTIFACT`, `FETCH_URL`, `JAR`, `WAR`, `NODE`, `DOCKER`). Keys belong to a config name, and all configs
   share one directory, so without this a `save-artifact` key would unpack an arbitrary archive
   via `/zip-archive/` into the same `dir`. A config **without** `type`, or with an unknown one, is
   refused — the type is never guessed (a guess would make a config dropped into the directory
   without a type valid).
5. The key matches one of the config's `apiKeys`: the agent hashes the sent key (SHA-256) and
   compares the hash, in constant time, with every `sha256:<hex>` entry. The sent value is never
   compared with an entry as it is — the hash from a config is not a key, and a plaintext entry
   never matches. The map value is only a label.

Every refusal is the same **401** with the message `Unauthorized` — an unknown command, a broken
config and a wrong key look identical from outside, and no path of the config directory leaves the agent.
The reason (step, command name, the loader's exception type) goes to the agent log only; keys and
loader exception messages are never logged.

- Keys are stored as **SHA-256 hashes** (`sha256:<64 lowercase hex>`), made by
  `java -jar dc-agent.jar hash-key` or by the control plane, which refuses a new key shorter
  than 32 characters. The keys are machine-made random strings, so a plain SHA-256 cannot be
  reversed; a slow hash would cost CPU on every (also unauthenticated) call and protect only weak
  keys. Reading the config directory gives no working key; **writing** to it is still as good as
  root — keep it root-only.
- The `sha256:` prefix is reserved in any letter case: a malformed entry with it is an error,
  never a plaintext key to hash again.
- A config without `apiKeys` (or with an empty map) refuses every request.

## What the agent checks at start

Before its port opens the agent locks `/opt/dc-agent/config` (`.dc-agent.lock`, held while it runs;
a second agent on the same directory refuses to start) and:

1. checks every config as found — a problem refuses the start and nothing is written, not even the
   lock file;
2. hashes every plaintext key. Each rewritten file is a new file created `0600`, given the mode and
   owner of the one it replaces, `fsync`ed and renamed over it, then the directory is `fsync`ed:
   no partial file, no moment where others can read it, no backup with the old keys, and a power
   loss cannot bring the plaintext back. A `.yml` becomes `<name>.json`. A rewrite that would
   outgrow the 1 MiB limit refuses the start before anything is written. A second start changes
   nothing;

   One window is left by design: a `.yml` is migrated as "write `<name>.json`, then delete
   `<name>.yml`". A crash between the two leaves both — the YAML still with its plaintext keys —
   and the next start refuses with "`<name>.json` and `<name>.yml` both exist". Move the YAML
   aside (`mv <name>.yml <name>.yml.aside` — no longer a config name), run
   `java -jar dc-agent.jar check-config` to check the JSON, then delete the `.aside` file (it holds
   the plaintext keys) and start again;
3. checks everything again with no plaintext key allowed.

`java -jar dc-agent.jar check-config` runs steps 1 and 3 on the result of step 2 kept in memory —
it writes nothing. The control plane writes its commands the same way (atomically, `0600` for a
new file) after the same checks, and answers `INVALID` instead of writing a config the agent would
refuse.

## Where a command may write

A command's `dir` — and the directory of `jarFilename` / `warFilename` — is checked as it will be
used: absolute, no `.` or `..` segments, resolved component by component with every symbolic link
followed (and every link on the way remembered; a missing tail is kept as written). It must:

- lie **strictly inside** one of `COMMAND_DIRS` (`/opt,/srv` by default) — an allow-list, so a
  forgotten system directory (`/etc/cron.d`, `/root/.ssh`, `/var/spool/cron`) cannot be reached;
- not be, contain or lie inside a protected path: the agent's home `/opt/dc-agent` and its
  entries, the config directory, the agent jar as started (`java -jar <path>`) and as loaded,
  `SERVICES_DIR`, `SERVICES_DEFINITION_DIR`, every service in them, and what daemontools runs
  from a service — `run`, `finish`, `log`, `log/run`, `log/finish`, `env` and `log/env` with
  their files. A protected path counts **with every link on its way**: replacing a link the agent
  or a service reads through would be as good as replacing the target.

A JAR/WAR/NODE `serviceDir` (default `/service/<serviceName>`) must be a service directly in
`SERVICES_DIR` or `SERVICES_DEFINITION_DIR`, so the service it restarts is one of the protected
ones. The protected set is computed on every check, because services and links change while the
agent runs.

Not covered: what a `run` script starts by itself (`exec /opt/app/bin/start.sh` — `/opt/app/bin`
may be a command's `dir`), a system directory bind-mounted inside `COMMAND_DIRS` (only root can
create such a mount — do not), and a link swapped inside `dir` between the check and the write
(that needs write access to `dir`).

## Path handling

### ZIP extraction (Zip Slip guard)
`zip-archive`, `zip-dirs`, and the `node` deploy all extract archives through a guarded writer
(`SafeFiles.createFileGuarded`). It canonicalizes the target directory and each entry's
destination and requires the destination to stay inside the target directory; a `../` entry or
an absolute-path entry is rejected with a `SecurityException`. Each file is written as a new
file renamed over the old one: a symbolic link (dangling or not) or a hard link at its place is
replaced, the file it pointed to stays as it was. A new file gets the mode the agent's umask
gives, not the mode of the file it replaces. The `zip-dirs` URL sub-path is
additionally restricted to a strict character whitelist (`0-9 a-z A-Z . - _`), the segments `.`
and `..` are refused, and the resolved directory must stay inside the canonical `dir` (a link
inside `dir` that leads out is rejected); it is created only after authorization.

### Versioned publication (`zip-archive-version`)
[zip-archive-version](/dc-agent/commands/zip-archive-version/) does not use the guarded writer: it
reads the ZIP with commons-compress (which exposes the Unix mode) and refuses, before anything is
created in `dir`, every entry it cannot write as a plain file inside the version — a `\` in a name
(checked on the stored bytes: the library itself turns `\` into `/` for MS-DOS-made entries),
absolute names, empty, `.` and `..` segments, symbolic links and other special entries, duplicate
names, a file that is also a directory. The Unicode Path extra field is ignored, so it cannot
replace a checked name. Writing never follows a link: the version is staged in a fresh
`.<version>.new-<random>` directory (every directory created one segment at a time, every file with
`CREATE_NEW`/`NOFOLLOW_LINKS`), the pointer and the digest are replaced by an atomic rename, a
leftover staging entry that is a link is unlinked, not followed. Modes are explicit — files `0644`,
directories `0755` — so the command is not for secrets. Limits (`maxUploadBytes`, `maxBytes` by bytes
actually read, `maxEntries`, at most two uploads in flight per agent) bound what an authorized
client can make the agent store. `reloadUrl` comes from the config only; redirects are not
followed; `reloadHeaders` values are never logged and are masked in the control-plane command list.

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
inside `dir` that lead out are rejected), like the ZIP guard; the file is written as a new file
renamed over the old one, so a link at its place is replaced, not written through. `{version}` must not contain `..`;
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
- Give every exposed endpoint a strong, random `api-key` (32+ characters) and store only its
  `sha256:` hash; rotate by listing multiple keys.
- Run `java -jar dc-agent.jar check-config` with the new jar before an upgrade.
- Keep `COMMAND_DIRS` as narrow as the commands need; never mount a system directory inside it.
- Keep `/opt/dc-agent/config` root-only and out of any directory a web server serves.
- After upgrading the agent, run `DOCKER_CHECK` for every service: unknown keys in `dc-docker.yml`
  (typos that used to be ignored, e.g. in `securityContext`) now fail the push.
- Keep `TEMP_DIR`, the deploy directories and every `save-artifact` `dir` on paths only root can
  change (the agent refuses an unsafe `TEMP_DIR`).
- Set your own `CONTROL_PLANE_TOKEN` (required with `CONTROL_PLANE_ENABLED=true`); leave the
  control plane off unless needed.
