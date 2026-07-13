# Local verification harness

A repeatable local run of the whole system — the **dc-agent** per-host agent, the
**dc-operator** control plane, and this **dc-agent-react** frontend — wired together
with local test config, so you can manually verify the UI before cutting a release.

The frontend is verified in **two modes**:

| Mode       | Frontend served by                                                       | Open                               | Mirrors                                                                  |
|------------|--------------------------------------------------------------------------|------------------------------------|--------------------------------------------------------------------------|
| `separate` | `yarn dev` (Rsbuild, :3000) proxying the API to the operator             | http://localhost:3000/dc-operator/ | day-to-day local dev                                                     |
| `bundled`  | dc-operator itself (:8052), UI baked into the jar via `-Pfrontend-local` | http://localhost:8052/dc-operator/ | a **real release** (operator unpacks `dc-agent-react-dist-<tag>.tar.gz`) |

```
frontend (:3000 dev  or  baked into operator)
        │  POST /dc-operator/api/*
        ▼
   dc-operator :8052 ──── reads ──▶ git config repo (.run/config, apps/ + tasks/)
        │  agent fleet (operator-config.yaml)
        ▼
   dc-agent :8051  (local, no Docker needed)
```

## Prerequisites

- **JDK 21** — required to build/run the Java backend from source. If `java -version`
  isn't 21, install one and either put it on `PATH`, or let the harness find it:
  - macOS: `brew install openjdk@21` (the scripts auto-detect via `/usr/libexec/java_home -v 21`)
  - or point at any JDK 21 explicitly: `export JAVA_HOME_21=/path/to/jdk-21`
- **Access to the private Maven repo `https://maven.pne.io`** — the backend build pulls
  `com.payneteasy.*` deps from it (payneteasy network / VPN).
- **Node + Yarn** and the **`dc-agent-react` frontend checked out** as a sibling of this
  repo (`../dc-agent-react`). Override with `FRONTEND_DIR=/path/to/dc-agent-react`.
- This harness lives **inside the `dc-agent` backend repo** (`verify/`); the backend is built
  from `..` (its repo root).

## Quick start

```bash
# separate mode: backend + local agent + `yarn dev`
verify/up.sh separate --build      # --build compiles the backend jars the first time

# bundled + logs
verify/up.sh bundled --build &&  tail -f verify/.run/logs/*.log

# …verify in the browser (see checklist below), then:
verify/down.sh

# bundled mode: local dist baked into the operator jar, served at :8052
verify/up.sh bundled --build
verify/down.sh
```

`--build` compiles the backend (and, for `bundled`, runs `yarn build` and re-bakes the
UI). Omit it on later runs to reuse the jars. Logs stream to `verify/.run/logs/`.

## What each script does

| Script                                                 | Purpose                                                                         |
|--------------------------------------------------------|---------------------------------------------------------------------------------|
| `up.sh <mode> [--build]`                               | Orchestrate build → config → agent → operator → (frontend).                     |
| `down.sh`                                              | Stop the agent, operator, and dev server.                                       |
| `build-backend.sh <mode>`                              | Build `dc-agent.jar` + `dc-operator.jar` (bundled bakes in the local `dist/`).  |
| `setup-config.sh`                                      | Create the config **git repo** `.run/config` (+ a bare `origin`).               |
| `setup-services.sh`                                    | Write daemontools `supervise/status` service dirs so the Service list is populated. |
| `bin/svc`                                              | Fake daemontools `svc` (bash) — Up/Down/etc. actions rewrite `supervise/status`. |
| `write-idea-env.sh`                                    | Write `.run/dc-agent.env` + `.run/dc-operator.env` (IDEA EnvFile) for running from the IDE. |
| `run-agent.sh` / `run-operator.sh` / `run-frontend.sh` | Launch one component in the background.                                         |
| `add-commit.sh`                                        | Push a new app commit to `origin` so **Pull from Git** fetches it (success flashbar). |
| `lib.sh` / `lib-status.sh`                             | Shared paths/tokens; daemontools `supervise/status` read-write helpers.         |

All state lives under `verify/.run/` (gitignored). `rm -rf verify/.run` to reset.

## Run the backend from IntelliJ IDEA

Every start writes `.run/dc-agent.env` and `.run/dc-operator.env` (the harness itself launches
from them, so they always match). To run/debug the backend in the IDE instead of the harness jars:

1. Prepare state + env files once: `verify/up.sh separate` then `verify/down.sh` (leaves `.run/`
   populated — config repo, agent config, services, and the two `.env` files — but frees the ports).
2. In IDEA install the **EnvFile** plugin. Create two *Application* run configs:
   - `com.payneteasy.dcagent.DcAgentApplication` (module `dc-agent-app`) → EnvFile: `verify/.run/dc-agent.env`
   - `com.payneteasy.dcagent.operator.DcAgentOperatorApplication` (module `dc-agent-operator`) → EnvFile: `verify/.run/dc-operator.env`
3. Run the agent, then the operator, from IDEA. Open http://localhost:8052/dc-operator/ (or run
   `yarn dev` in `../dc-agent-react` for the separate-mode UI).

Only one process may own a port, so don't run a component from both the harness and IDEA at once.

## Manual verification checklist

Run through this in **both** modes (`separate` at :3000, `bundled` at :8052). The URL is
printed by `up.sh`.

- [ ] **Shell loads** at `…/dc-operator/`; the left nav lists Applications, Service list,
      dc-agent list, Command list, Git repo, Logs. Browser tab title uses the `dc: ` prefix.
- [ ] **Applications** — `hello-local` is listed (read from the git config). Selecting a
      row loads its status/detail; the status column resolves (the drift check reaches the
      agent — expect a "will create" / DRIFT result, since no service is actually deployed).
- [ ] **dc-agent list** — the `local` agent shows as **reachable** with app-status data
      (this proves the `appStatusToken` round-trips operator → agent).
- [ ] **Service list** — shows the local agent's daemontools services from
      `setup-services.sh`: `echo-svc` (Up), `sample-queue` (Paused), `demo-timer` (Down),
      with pid/when parsed from each `supervise/status` file. Open a service and use the
      **Up / Down / Terminate / Hangup** actions: the fake `bin/svc` rewrites `supervise/status`,
      so on refresh the state flips (e.g. Down → Up, pid appears).
- [ ] **Command list** — shows the example non-docker commands on host `local`
      (`app-config` ZIP_ARCHIVE, `billing` JAR, `fetch-url` FETCH_URL, `frontend` NODE),
      each with its parameters (api-key owner labels only — the secret keys are never sent).
- [ ] **Git repo** — shows branch `main` and the seed commit(s). Run `verify/add-commit.sh`
      in another terminal (it pushes a commit to `origin`), then click **Pull from Git**:
      expect the green success flashbar *"Pulled 1 new commit from main…"* that auto-dismisses
      after ~3s, and the new app appears in Applications. (Requires the operator built with the
      local-pull fix — see the note below.)
- [ ] **Quick-Pull on Applications** — the header **Pull from Git** button + `main · updated …`
      hint behave the same (run `add-commit.sh` again, click it).
- [ ] **Error handling** — `verify/down.sh` the stack (or just stop the agent) and reload an
      agent-backed screen: the global error flashbar appears with the request error.
- [ ] **bundled mode only** — confirm the operator serves the assets itself
      (`view-source` / network tab shows `/dc-operator/assets/index.js` + `index.css` from
      :8052) and **no** dev server is running.

### Backend fix for local `git pull`
Stock `GitServiceImpl.pull` hard-cast the jgit transport to `SshTransport`, so it only worked
against an SSH remote and threw `ClassCastException` on this harness's local `file://` remote.
The fix (in this repo, `dc-agent-operator/…/operator/service/git/impl/GitServiceImpl.java`)
guards that cast with `instanceof SshTransport` — SSH remotes unchanged; local/http transports
pass through. **Rebuild the operator** (`verify/up.sh <mode> --build`) so the running jar has it.

### Known gaps (expected, not bugs)
- The Service list entries are **synthetic daemontools status files** (`setup-services.sh`),
  driven by the fake `bin/svc`. Up/Down/etc. actions flip the recorded state, but there are no
  real processes behind the pids — it exercises the UI + agent action plumbing, not a real
  supervisor. `svc` is picked up from `DAEMONTOOLS_SVC_PATH` at agent startup, so **restart the
  agent** (`run-agent.sh` / `up.sh`) after adding it.
- **Logs** screen and the **info popovers** are still frontend-only mocks
  (`src/pages/logs/api/logs-list.ts`, `src/components/info/api/info.ts`) — canned data.

## Security note

The harness uses only **local, non-production** tokens (see `lib.sh`). It never reads the
live credentials that exist elsewhere on disk — `deploy-qa-5.sh` (a dc-agent api-key) and
`../dc-operator-local-test/config/operator-config.yaml` (qa7/8/9 tokens). Those should be
rotated and kept out of version control.
