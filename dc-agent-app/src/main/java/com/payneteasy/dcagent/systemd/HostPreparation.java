package com.payneteasy.dcagent.systemd;

import com.payneteasy.dcagent.systemd.HostFacts.Attrs;
import com.payneteasy.dcagent.systemd.HostFacts.Type;
import com.payneteasy.dcagent.systemd.SandboxPlan.Kind;
import com.payneteasy.dcagent.systemd.SandboxPlan.Rule;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Prepares the host for the sandboxed agent, in Java (no generated shell). {@link #decide()} looks at
 * the host and lists the steps: a change it can make safely, or a hand-off for a human with the exact
 * commands. It changes a path only where root alone controls it — the path and every directory above it
 * are root's, writable by root only, no link, no extended ACL — so no other user can swap anything while
 * it acts. Recursive grants only on trees root alone controls, without links, mounts or ACLs, bottom-up.
 * {@link #apply} makes the changes, then {@link #verify} checks everything as the agent would use it.
 */
final class HostPreparation {

    static final String USER = "dc-agent";

    /** One change, made through {@link HostActions}. */
    interface Action {
        void run(HostActions aActions) throws IOException;
    }

    /**
     * A change ({@code action} set) or a hand-off ({@code commands} for a human). {@code safe} is asked
     * again right before the change (the host may have changed since — an earlier step included).
     */
    record Step(String subject, String text, Action action, java.util.function.BooleanSupplier safe, List<String> commands) {
        boolean handoff() {
            return action == null;
        }
    }

    private static final Path ENV_FILE    = Path.of("/etc/dc-agent/env");
    private static final Path SYSCTL_FILE = Path.of("/etc/sysctl.d/90-dc-agent.conf");

    private final SandboxPlan  plan;
    private final UnitSettings settings;
    private final Path         configDir;
    private final Path         agentHome;
    private final HostFacts    facts;
    private final List<Step>   steps   = new ArrayList<>();
    /** Directories a step below already gives what the agent needs (not passed through again). */
    private final Set<Path>    handled = new LinkedHashSet<>();

    HostPreparation(SandboxPlan aPlan, UnitSettings aSettings, Path aConfigDir, Path aAgentHome, HostFacts aFacts) {
        plan      = aPlan;
        settings  = aSettings;
        configDir = aConfigDir;
        agentHome = aAgentHome;
        facts     = aFacts;
    }

    List<Step> decide() {
        account();
        configDir();
        if (plan.gitlabKeys) {
            keysDir(agentHome.resolve("keys"));
        }
        serviceRoot(Path.of(settings.servicesDir()));
        serviceRoot(Path.of(settings.servicesDefinitionDir()));
        envFile();
        tcpFastOpen();
        // deeper trees first: a grant on an outer directory must not come before a walk inside it
        List<Map.Entry<Path, List<String>>> trees = new ArrayList<>(plan.trees.entrySet());
        trees.sort(Comparator.comparingInt((Map.Entry<Path, List<String>> e) -> e.getKey().getNameCount()).reversed());
        for (Map.Entry<Path, List<String>> tree : trees) {
            if (!insideWholeTree(tree.getKey()) || facts.attrs(tree.getKey()).isEmpty()) { // granted with the tree around it, unless missing
                tree(tree.getKey(), tree.getValue());
            }
        }
        for (SandboxPlan.Service service : plan.services) {
            supervise(service);
        }
        logs();
        listed();
        traverse();
        return steps;
    }

    // ── the agent's own things ─────────────────────────────────────────────

    private void account() {
        if (!facts.userExists(USER)) {
            change("account " + USER, "create it (system, no login, no home)", a -> a.useradd(USER));
        }
    }

    private void configDir() {
        Path c = configDir;
        handled.add(c);
        if (!rootOnly(c)) {
            handoff(c, "is not root's alone", "chown root:" + USER + " " + q(c) + " && chmod 0750 " + q(c));
            return;
        }
        List<Path> entries = entries(c);
        if (!facts.acl(c).isEmpty() || entries.stream().anyMatch(e -> !facts.acl(e).isEmpty())) {
            handoff(c, "or an entry in it has an extended ACL (a mode change would set its mask)", "getfacl -R " + q(c));
            return;
        }
        if (entries.stream().anyMatch(e -> facts.attrs(e).map(a -> a.type() == Type.FILE && a.links() > 1).orElse(false))) {
            handoff(c, "holds a file with more than one hard link — a mode change would reach it elsewhere", "ls -li " + q(c));
            return;
        }
        ensure(c, "root", USER, 0750);
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (facts.attrs(entry).map(a -> a.type() == Type.FILE).orElse(false)
                    && (name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml"))) {
                ensure(entry, "root", USER, 0640);
            }
        }
        Path lock = c.resolve(".dc-agent.lock");
        Optional<Attrs> attrs = facts.attrs(lock);
        if (attrs.isEmpty()) {
            change(lock.toString(), "create the config lock (" + USER + ", 0600)", a -> a.create(lock, USER, "rw-------", ""));
        } else if (plainFile(lock)) {
            ensure(lock, USER, USER, 0600);
        } else {
            handoff(lock, "is not a plain file of its own", "rm " + q(lock) + " && install -o " + USER + " -g " + USER + " -m 0600 /dev/null " + q(lock));
        }
    }

    private void keysDir(Path aKeys) {
        handled.add(aKeys);
        if (facts.attrs(aKeys).isEmpty()) {
            handoff(aKeys, "is missing (GITLAB_JWT keys need issuer files)", "dc-agent fetch-jwks <issuer> --issuer https://…");
            return;
        }
        List<Path> entries = entries(aKeys);
        if (!rootOnly(aKeys) || !facts.acl(aKeys).isEmpty() || entries.stream().anyMatch(e -> !facts.acl(e).isEmpty()
                || facts.attrs(e).map(x -> x.type() == Type.FILE && x.links() > 1).orElse(false))) {
            handoff(aKeys, "is not root's alone, or holds an ACL or a hard-linked file", "chgrp -R " + USER + " " + q(aKeys));
            return;
        }
        ensure(aKeys, null, USER, 0750);
        for (Path entry : entries) {
            if (facts.attrs(entry).map(x -> x.type() == Type.FILE).orElse(false)) {
                ensure(entry, null, USER, 0640);
            }
        }
    }

    private void serviceRoot(Path aRoot) {
        if (facts.attrs(aRoot).isPresent()) {
            return;
        }
        if (rootOnly(aRoot.getParent())) {
            change(aRoot.toString(), "create it empty (the launcher needs every path it is given)", a -> a.mkdir(aRoot, null, "rwxr-xr-x"));
        } else {
            handoff(aRoot, "is missing", "install -d -m 0755 " + q(aRoot));
        }
    }

    private void envFile() {
        Optional<Attrs> attrs = facts.attrs(ENV_FILE);
        if (attrs.isEmpty()) {
            Path dir = ENV_FILE.getParent();
            boolean dirMissing = facts.attrs(dir).isEmpty();
            if (dirMissing ? rootOnly(dir.getParent()) : rootOnly(dir)) {
                String token = HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
                change(ENV_FILE.toString(), "create it with a new APP_STATUS_TOKEN (root, 0600)", a -> {
                    if (dirMissing) {
                        a.mkdir(dir, null, "rwx------");
                    }
                    a.create(ENV_FILE, null, "rw-------", "APP_STATUS_TOKEN=" + token + "\n");
                });
            } else {
                handoff(dir, "is not root's alone", "install -d -m 0700 /etc/dc-agent && (umask 077; printf 'APP_STATUS_TOKEN=%s\\n' \"$(openssl rand -hex 32)\" > " + ENV_FILE + ")");
            }
            return;
        }
        if (!plainFile(ENV_FILE) || !rootOnly(ENV_FILE)) {
            handoff(ENV_FILE, "is not a plain file of root's alone", "ls -l " + q(ENV_FILE));
            return;
        }
        for (String line : facts.lines(ENV_FILE)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            String name = trimmed.substring(0, trimmed.indexOf('=')).trim();
            if (!UnitSettings.SECRET_VARIABLES.contains(name)) {
                handoff(ENV_FILE, "sets " + name + " — it would override the drop-in (EnvironmentFile wins); only "
                        + UnitSettings.SECRET_VARIABLES + " belong there", "edit " + ENV_FILE + ": remove " + name);
            }
        }
    }

    private void tcpFastOpen() {
        Optional<Attrs> attrs = facts.attrs(SYSCTL_FILE);
        if (attrs.isEmpty() && rootOnly(SYSCTL_FILE.getParent())) {
            change(SYSCTL_FILE.toString(), "write net.ipv4.tcp_fastopen = 0 (the launcher refuses TCP Fast Open anyway)",
                    a -> a.create(SYSCTL_FILE, null, "rw-r--r--", "net.ipv4.tcp_fastopen = 0\n"));
        } else if (attrs.isPresent() && !(plainFile(SYSCTL_FILE) && rootOnly(SYSCTL_FILE))) {
            handoff(SYSCTL_FILE, "is not a plain file of root's alone — not read, not applied", "ls -l " + q(SYSCTL_FILE));
        }
        List<String> current = facts.lines(Path.of("/proc/sys/net/ipv4/tcp_fastopen"));
        if (current.isEmpty() || !"0".equals(current.get(0).trim())) {
            change("net.ipv4.tcp_fastopen", "set it to 0 now (only this setting)", a -> a.sysctl("net.ipv4.tcp_fastopen=0"));
        }
    }

    // ── command directories ────────────────────────────────────────────────

    /** A command dir: everything in it (null), itself (empty) or these subtrees — subtrees before the dir. */
    private void tree(Path aDir, List<String> aSubtrees) {
        handled.add(aDir);
        if (facts.attrs(aDir).isEmpty()) {
            if (rootOnly(aDir.getParent())) {
                // inside a tree granted as a whole: root's, so the grant of that tree (after it) still finds a root tree
                boolean covered = insideWholeTree(aDir);
                change(aDir.toString(), covered ? "create it (root, 0755; granted with the tree around it)" : "create it for " + USER + " (0755)",
                        () -> rootOnly(aDir.getParent()) && facts.attrs(aDir).isEmpty(), a -> a.mkdir(aDir, covered ? null : USER, "rwxr-xr-x"));
            } else {
                handoff(aDir, "is missing and its parent is not root's alone", "install -d -o " + USER + " -g " + USER + " -m 0755 " + q(aDir));
            }
            return;
        }
        if (aSubtrees == null) {
            if (facts.agentObstacle(aDir) == null) {
                return;
            }
            grantTreeOrHandoff(aDir);
            return;
        }
        for (String sub : aSubtrees) {
            if (!plainName(sub)) {
                handoff(aDir, "the subtree " + q(sub) + " would lie outside it — not changed", "ls -la " + q(aDir));
                return;
            }
            Path path = aDir.resolve(sub);
            handled.add(path);
            if (facts.attrs(path).isPresent() && facts.agentObstacle(path) != null) {
                grantTreeOrHandoff(path);
            }
        }
        if (!facts.agentCan(aDir, "-r", "-w", "-x") || sticky(aDir)) {
            if (rootOnly(aDir) && facts.acl(aDir).isEmpty() && !sticky(aDir)) {
                change(aDir.toString(), "give " + USER + " rwx on the directory itself", () -> rootOnly(aDir) && facts.acl(aDir).isEmpty(),
                        a -> a.setfacl("u:" + USER + ":rwx,d:u:" + USER + ":rwx", List.of(aDir)));
            } else {
                handoff(aDir, USER + " cannot list and write in it (owner " + owner(aDir) + ")", "setfacl -m u:" + USER + ":rwx " + q(aDir));
            }
        }
    }

    private void grantTreeOrHandoff(Path aDir) {
        String obstacle = facts.agentObstacle(aDir);
        if (rootTree(aDir)) {
            change(aDir.toString(), "give " + USER + " rwX on the whole tree, bottom-up (only root can change it)", () -> rootTree(aDir), a -> grantTree(a, aDir));
        } else {
            handoff(aDir, "is not writable for " + USER + " throughout (first obstacle: " + obstacle + ") and someone else can change it",
                    "decide: chown -R " + USER + " " + q(aDir) + "   |   a directory of its own for the agent   |   setfacl -R -m u:"
                            + USER + ":rwX,d:u:" + USER + ":rwX " + q(aDir) + " (knowing who else can change it)");
        }
    }

    /** Default ACLs on the directories, then access ACLs, both bottom-up: no ancestor of a changed path is writable yet. */
    private void grantTree(HostActions aActions, Path aDir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        List<Path> all  = new ArrayList<>();
        List<Path> walk = facts.walk(aDir);
        if (walk == null) {
            throw new IOException(aDir + " cannot be walked completely");
        }
        for (Path path : walk) {
            Type type = facts.attrs(path).map(Attrs::type).orElse(Type.OTHER);
            if (type == Type.DIR) {
                dirs.add(path);
            }
            if (type == Type.DIR || type == Type.FILE) {
                all.add(path);
            }
        }
        aActions.setfacl("d:u:" + USER + ":rwX", dirs);
        aActions.setfacl("u:" + USER + ":rwX", all);
    }

    // ── services: supervise fifos and status ───────────────────────────────

    private void supervise(SandboxPlan.Service aService) {
        Path s = aService.supervise();
        if (!handled.add(s)) {
            return; // the same service in another config: one change
        }
        Path control = s.resolve("control");
        Path ok      = s.resolve("ok");
        Path status  = s.resolve("status");
        for (Path fifo : List.of(control, ok)) {
            if (facts.attrs(fifo).map(a -> a.type() != Type.FIFO).orElse(true)) {
                handoff(fifo, "is not a fifo — is the service supervised?", "svstat " + aService.dir());
                return;
            }
            if (!plainFifo(fifo)) { // another name of the same fifo would control another service
                handoff(fifo, "is a fifo with more than one name (a hard link) — another service could be controlled through it", "ls -li " + q(fifo));
                return;
            }
        }
        boolean fifoOk   = facts.agentCan(s, "-x") && facts.agentCan(control, "-w") && facts.agentCan(ok, "-w");
        boolean statusOk = facts.attrs(status).isEmpty() || facts.agentCan(status, "-r");
        if (fifoOk && statusOk) {
            return;
        }
        // chmod or setfacl on a path with an extended ACL sets its mask: it could unmask someone else's entry
        java.util.function.BooleanSupplier safe = () -> rootOnly(s) && facts.acl(s).isEmpty()
                && plainFifo(control) && plainFifo(ok) && facts.acl(control).isEmpty() && facts.acl(ok).isEmpty()
                && (statusOk || (plainFile(status) && facts.acl(status).isEmpty()));
        if (safe.getAsBoolean()) {
            change(s.toString(), (fifoOk ? "" : "fifos control, ok for " + USER + " (group, 0620; the directory 0710)")
                    + (fifoOk || statusOk ? "" : "; ") + (statusOk ? "" : "status readable for " + USER + " (also as supervise replaces it)"),
                    safe, a -> {
                if (!fifoOk) {
                    a.chown(s, null, USER);
                    a.chown(control, null, USER);
                    a.chown(ok, null, USER);
                    a.chmod(s, "rwx--x---");
                    a.chmod(control, "rw--w----");
                    a.chmod(ok, "rw--w----");
                }
                if (!statusOk) {
                    a.setfacl("d:u:" + USER + ":r--", List.of(s));
                    a.setfacl("u:" + USER + ":r--", List.of(status));
                }
            });
        } else {
            handoff(s, "needs changes root cannot make safely here (owner " + owner(s) + ", or an ACL already there)",
                    "chgrp " + USER + " " + q(s) + " " + q(control) + " " + q(ok) + " && chmod 0710 " + q(s) + " && chmod 0620 " + q(control) + " " + q(ok) + "",
                    "setfacl -m d:u:" + USER + ":r-- " + q(s) + " && setfacl -m u:" + USER + ":r-- " + q(status) + "   # if " + USER + " cannot read the status");
        }
    }

    // ── logs, listed directories, the way to everything ────────────────────

    private void logs() {
        Map<Path, List<Path>> byDir = new LinkedHashMap<>();
        for (SandboxPlan.Service service : plan.services) {
            if (service.logDir() != null) {
                byDir.computeIfAbsent(service.logDir(), d -> new ArrayList<>()).add(service.logFile());
            }
        }
        for (Map.Entry<Path, List<Path>> entry : byDir.entrySet()) {
            Path dir = entry.getKey();
            handled.add(dir);
            boolean    dirOk = facts.agentCan(dir, "-r", "-x");
            List<Path> bad   = entry.getValue().stream().filter(f -> facts.attrs(f).isPresent() && !facts.agentCan(f, "-r")).toList();
            if (dirOk && bad.isEmpty()) {
                continue;
            }
            java.util.function.BooleanSupplier safe = () -> rootOnly(dir) && facts.acl(dir).isEmpty()
                    && bad.stream().allMatch(f -> plainFile(f) && facts.acl(f).isEmpty());
            if (safe.getAsBoolean()) {
                change(dir.toString(), "logs readable for " + USER + " (r-x, new files r--" + (bad.isEmpty() ? "" : "; " + bad) + ")", safe, a -> {
                    a.setfacl("u:" + USER + ":r-x,d:u:" + USER + ":r--", List.of(dir));
                    if (!bad.isEmpty()) {
                        a.setfacl("u:" + USER + ":r--", bad);
                    }
                });
            } else {
                handoff(dir, USER + " cannot read its logs " + entry.getValue() + " (owner " + owner(dir) + ")",
                        "setfacl -m u:" + USER + ":r-x,d:u:" + USER + ":r-- " + q(dir) + (bad.isEmpty() ? "" : " && setfacl -m u:" + USER + ":r-- " + quoted(bad)));
            }
        }
    }

    private void listed() {
        for (Path dir : plan.listed) {
            if (inRegion(dir) || !handled.add(dir) || facts.attrs(dir).isEmpty() || facts.agentCan(dir, "-r", "-x")) {
                continue;
            }
            if (rootOnly(dir) && facts.acl(dir).isEmpty()) {
                change(dir.toString(), USER + " lists it (r-x)", () -> rootOnly(dir) && facts.acl(dir).isEmpty(), a -> a.setfacl("u:" + USER + ":r-x", List.of(dir)));
            } else {
                handoff(dir, USER + " cannot list it (owner " + owner(dir) + ")", "setfacl -m u:" + USER + ":r-x " + q(dir));
            }
        }
    }

    private void traverse() {
        for (Path dir : ancestors(plan)) {
            if (isHandled(dir) || facts.attrs(dir).map(a -> a.type() != Type.DIR).orElse(true) || facts.agentCan(dir, "-x")) {
                continue;
            }
            if (rootOnly(dir) && facts.acl(dir).isEmpty()) {
                change(dir.toString(), USER + " passes through it (--x)", () -> rootOnly(dir) && facts.acl(dir).isEmpty(), a -> a.setfacl("u:" + USER + ":--x", List.of(dir)));
            } else {
                handoff(dir, USER + " cannot pass through it (owner " + owner(dir) + ")", "setfacl -m u:" + USER + ":--x " + q(dir));
            }
        }
    }

    /** Inside (or equal to) a recursive region of another tree — a whole tree, a NODE/WAR subtree: granted with it. */
    private boolean insideWholeTree(Path aDir) {
        for (Map.Entry<Path, List<String>> tree : plan.trees.entrySet()) {
            if (tree.getKey().equals(aDir)) {
                continue;
            }
            for (Path region : regions(tree.getKey(), tree.getValue())) {
                if (aDir.startsWith(region)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The directories a tree grants recursively. */
    private static List<Path> regions(Path aDir, List<String> aSubtrees) {
        return aSubtrees == null ? List.of(aDir) : aSubtrees.stream().filter(HostPreparation::plainName).map(aDir::resolve).toList();
    }

    /** One name of an entry inside a directory: not empty, not . or .., no separator. */
    static boolean plainName(String aName) {
        return !aName.isEmpty() && !".".equals(aName) && !"..".equals(aName) && aName.indexOf('/') < 0 && aName.indexOf('\0') < 0;
    }

    /** A directory a step above already covers (itself or inside a granted tree). */
    private boolean isHandled(Path aDir) {
        for (Path path : handled) {
            if (aDir.equals(path)) {
                return true;
            }
        }
        return inRegion(aDir);
    }

    /** Inside a recursive region of any tree (granted with it). */
    private boolean inRegion(Path aDir) {
        for (Map.Entry<Path, List<String>> tree : plan.trees.entrySet()) {
            for (Path region : regions(tree.getKey(), tree.getValue())) {
                if (aDir.startsWith(region)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every directory above the paths the agent reaches, from / down, without the written ones. */
    static Set<Path> ancestors(SandboxPlan aPlan) {
        Set<Path> dirs = new TreeSet<>();
        for (Path path : aPlan.reached) {
            if (SandboxPlan.isProcSelf(path) || path.startsWith("/dev")) {
                continue;
            }
            for (Path dir = path.getParent(); dir != null; dir = dir.getParent()) {
                dirs.add(dir);
            }
        }
        for (Rule rule : aPlan.rules(Kind.RW)) {
            dirs.remove(rule.path());
        }
        return dirs;
    }

    // ── the rules of safety ────────────────────────────────────────────────

    /**
     * The path and every directory above it: exist, no link, root's, not writable by group or others, no ACL
     * entry granting write (the agent's included) — no one but root can swap any of them while we act.
     */
    boolean rootOnly(Path aPath) {
        if (aPath == null) {
            return false;
        }
        for (Path p = aPath; p != null; p = p.getParent()) {
            Optional<Attrs> attrs = facts.attrs(p);
            if (attrs.isEmpty() || attrs.get().type() == Type.LINK || !"root".equals(attrs.get().owner())
                    || attrs.get().writableByGroupOrOthers()) {
                return false;
            }
            for (String entry : facts.acl(p)) {
                if (!entry.startsWith("default:") && !entry.startsWith("mask:") && entry.matches("[^:]+:[^:]*:.w.*")) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A tree root alone controls: its directories root's and not writable by others, one file system, no hard links, no ACLs. */
    boolean rootTree(Path aDir) {
        if (!rootOnly(aDir) || facts.mountInside(aDir) != null || !facts.aclsInTree(aDir).isEmpty()) {
            return false;
        }
        long device = facts.attrs(aDir).map(Attrs::device).orElse(-1L);
        List<Path> walk = facts.walk(aDir);
        if (walk == null) {
            return false; // not fully walked: unknown, so not safe
        }
        for (Path path : walk) {
            Optional<Attrs> attrs = facts.attrs(path);
            if (attrs.isEmpty()) {
                return false;
            }
            Attrs a = attrs.get();
            if (a.type() == Type.DIR && (!"root".equals(a.owner()) || a.writableByGroupOrOthers() || a.device() != device)) {
                return false;
            }
            if (a.type() == Type.DIR && (a.mode() & 01000) != 0) {
                return false; // sticky: an ACL does not let the agent replace or delete root's entries there
            }
            if (a.type() != Type.DIR && a.type() != Type.LINK && a.links() > 1) {
                return false; // a hard link would carry the grant to a file outside the tree
            }
        }
        return true;
    }

    private boolean plainFifo(Path aPath) {
        return facts.attrs(aPath).map(a -> a.type() == Type.FIFO && a.links() == 1).orElse(false);
    }

    private boolean plainFile(Path aPath) {
        return facts.attrs(aPath).map(a -> a.type() == Type.FILE && a.links() == 1).orElse(false);
    }

    private boolean sticky(Path aDir) {
        return facts.attrs(aDir).map(a -> (a.mode() & 01000) != 0).orElse(false);
    }

    private String owner(Path aPath) {
        return facts.attrs(aPath).map(Attrs::owner).orElse("?");
    }

    private List<Path> entries(Path aDir) {
        List<Path> walk = facts.walk(aDir);
        return walk == null ? List.of(aDir.resolve("(unreadable)")) : walk.stream().filter(p -> aDir.equals(p.getParent())).toList();
    }

    /** Owner (null: keep), group and mode, set only where they differ. */
    private void ensure(Path aPath, String aOwner, String aGroup, int aMode) {
        Optional<Attrs> attrs = facts.attrs(aPath);
        if (attrs.isEmpty()) {
            return;
        }
        boolean ownerWrong = aOwner != null && !aOwner.equals(attrs.get().owner());
        boolean groupWrong = aGroup != null && !aGroup.equals(attrs.get().group());
        boolean modeWrong  = (attrs.get().mode() & 0777) != aMode;
        if (!ownerWrong && !groupWrong && !modeWrong) {
            return;
        }
        String mode = HostActions.Real.symbolic(aMode);
        Path parent = aPath.getParent();
        change(aPath.toString(), (aOwner != null ? aOwner : attrs.get().owner()) + ":" + aGroup + " " + Integer.toOctalString(aMode),
                () -> rootOnly(parent) && facts.acl(aPath).isEmpty() && facts.attrs(aPath).map(x -> x.type() != Type.LINK && x.links() == 1 || x.type() == Type.DIR).orElse(false), a -> {
            a.chown(aPath, ownerWrong ? aOwner : null, groupWrong ? aGroup : null);
            a.chmod(aPath, mode);
        });
    }

    private void change(String aSubject, String aText, Action aAction) {
        change(aSubject, aText, () -> true, aAction);
    }

    private void change(String aSubject, String aText, java.util.function.BooleanSupplier aSafe, Action aAction) {
        steps.add(new Step(aSubject, aText, aAction, aSafe, List.of()));
    }

    private void handoff(Path aSubject, String aText, String... aCommands) {
        steps.add(new Step(aSubject.toString(), aText, null, () -> false, List.of(aCommands)));
    }

    /** One shell word: in single quotes, a single quote as '\\'' — a hand-off command is copied as is. */
    static String q(Object aValue) {
        return "'" + String.valueOf(aValue).replace("'", "'\\''") + "'";
    }

    private static String quoted(List<Path> aPaths) {
        StringBuilder out = new StringBuilder();
        for (Path path : aPaths) {
            out.append(out.length() == 0 ? "" : " ").append(q(path));
        }
        return out.toString();
    }

    // ── output, apply, verify ──────────────────────────────────────────────

    static void print(List<Step> aSteps, List<String> aNotes, PrintStream aOut) {
        long changes  = aSteps.stream().filter(s -> !s.handoff()).count();
        long handoffs = aSteps.size() - changes;
        if (aSteps.isEmpty()) {
            aOut.println("Nothing to do: the host is ready for the sandboxed agent.");
        } else {
            aOut.println(changes + " change(s) to make, " + handoffs + " hand-off(s) for a human:");
        }
        for (Step step : aSteps) {
            aOut.println((step.handoff() ? "  HAND-OFF " : "  change   ") + step.subject() + " — " + step.text());
            for (String command : step.commands()) {
                aOut.println("             $ " + command);
            }
        }
        for (String note : aNotes) {
            aOut.println("  note     " + note);
        }
    }

    /** Makes the changes, in order; false (and the reason printed) when one fails. */
    static boolean apply(List<Step> aSteps, HostActions aActions, PrintStream aOut) {
        for (Step step : aSteps) {
            if (step.handoff()) {
                continue;
            }
            if (!step.safe().getAsBoolean()) {
                aOut.println("  SKIPPED  " + step.subject() + " — no longer safe to change (the host changed): run --prepare again");
                return false;
            }
            try {
                step.action().run(aActions);
                aOut.println("  done     " + step.subject() + " — " + step.text());
            } catch (IOException | RuntimeException e) {
                aOut.println("  FAILED   " + step.subject() + " — " + step.text() + ": " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** As the agent: every tree writable as its command needs, the services' fifos and status, the logs; problems listed. */
    List<String> verify() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Path, List<String>> tree : plan.trees.entrySet()) {
            Path dir = tree.getKey();
            if (tree.getValue() == null) {
                String obstacle = facts.agentObstacle(dir);
                if (obstacle != null) {
                    problems.add(dir + ": " + obstacle);
                }
                continue;
            }
            if (!facts.agentCan(dir, "-r", "-w", "-x")) {
                problems.add(dir + ": " + USER + " cannot list and write in it");
            }
            for (String sub : tree.getValue()) {
                Path path = dir.resolve(sub);
                String obstacle = facts.attrs(path).isPresent() ? facts.agentObstacle(path) : null;
                if (obstacle != null) {
                    problems.add(path + ": " + obstacle);
                }
            }
        }
        for (SandboxPlan.Service service : plan.services) {
            Path s = service.supervise();
            if (!facts.agentCan(s.resolve("control"), "-w") || !facts.agentCan(s.resolve("ok"), "-w")) {
                problems.add(s + ": " + USER + " cannot write control/ok");
            }
            if (facts.attrs(s.resolve("status")).isPresent() && !facts.agentCan(s.resolve("status"), "-r")) {
                problems.add(s.resolve("status") + ": not readable by " + USER);
            }
            if (service.logFile() != null && facts.attrs(service.logFile()).isPresent() && !facts.agentCan(service.logFile(), "-r")) {
                problems.add(service.logFile() + ": not readable by " + USER);
            }
        }
        return problems;
    }
}
