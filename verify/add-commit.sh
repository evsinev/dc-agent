#!/usr/bin/env bash
# Push a new app commit to the config repo's bare 'origin', leaving the operator's
# working checkout one commit BEHIND. Then clicking "Pull from Git" in the UI (Git repo
# screen or the Applications header Quick-Pull) fetches it — showing the green success
# flashbar with the new-commit count.
#
# Requires the operator built with the local-pull fix in GitServiceImpl (the transport
# cast is guarded by `instanceof SshTransport`, so a file:// remote no longer crashes).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ -d "$CONFIG_REMOTE" ]] || die "no config remote — run: verify/setup-config.sh"

work="$RUN_DIR/config-push"
rm -rf "$work"
git clone -q "$CONFIG_REMOTE" "$work"

n="$(find "$work/apps" -maxdepth 1 -name '*.yaml' | wc -l | tr -d ' ')"
name="extra-app-$n"
cat > "$work/apps/$name.yaml" <<YAML
appName  : $name
taskName : hello
taskType : DOCKER
taskHost : local
YAML

git -C "$work" -c user.email=verify@local -c user.name=verify add -A
git -C "$work" -c user.email=verify@local -c user.name=verify commit -q -m "Add $name (verify demo commit)"
git -C "$work" push -q origin HEAD:main

log "Pushed '$name' to origin (the operator's checkout is now 1 commit behind)."
log "Click 'Pull from Git' in the UI — expect the success flashbar: 'Pulled 1 new commit from main'."
