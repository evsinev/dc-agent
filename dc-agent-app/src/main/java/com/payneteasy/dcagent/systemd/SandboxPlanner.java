package com.payneteasy.dcagent.systemd;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.validation.CommandConfigFile;
import com.payneteasy.dcagent.core.config.validation.CommandPathPolicy;
import com.payneteasy.dcagent.core.config.validation.PathResolver;
import com.payneteasy.dcagent.core.daemontools.Supervise;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.dcagent.systemd.SandboxPlan.Kind;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.payneteasy.dcagent.core.util.Strings.hasText;

/**
 * Derives a {@link SandboxPlan} from the command configs — the paths each type touches, as its servlet
 * computes them. Everything it emits is resolved (links followed, as Landlock and systemd see it) and
 * from a safe character set; whatever it cannot grant safely is a problem, and then nothing is printed.
 */
public final class SandboxPlanner {

    /** Paths the generator writes into the unit and the script: nothing systemd or a shell could reinterpret. */
    static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._@+/-]*$");

    /** Limits of the launcher (dc-agent-landlock.c MAX_RULES, MAX_PORTS). */
    static final int MAX_RULES = 256;
    static final int MAX_PORTS = 64;

    /** Never a derived read path: the agent would read far more than the command needs. */
    private static final Set<String> BROAD = Set.of("/", "/etc", "/usr", "/var", "/opt", "/srv", "/home", "/root", "/run",
            "/proc", "/sys", "/dev", "/tmp", "/boot", "/bin", "/sbin", "/lib", "/lib64", "/mnt", "/media");

    /** Private in the unit (PrivateTmp): a command path there would not be the host's. */
    private static final List<Path> PRIVATE_TREES = List.of(Path.of("/tmp"), Path.of("/var/tmp"));

    /** Where a configured path under these makes ProtectHome=yes hide it. */
    private static final List<Path> HOME_TREES = List.of(Path.of("/home"), Path.of("/root"), Path.of("/run/user"));

    private static final List<Path> TRUST_STORES = List.of(Path.of("/etc/pki/java/cacerts"), Path.of("/etc/ssl/certs/java/cacerts"));

    private final Path              configDir;
    private final Path              agentHome;
    private final Path              agentBinary;
    private final UnitSettings      settings;
    private final CommandPathPolicy policy;
    private final List<Path>        commandRoots;
    private final SandboxPlan       plan = new SandboxPlan();

    public SandboxPlanner(Path aConfigDir, Path aAgentHome, Path aAgentBinary, UnitSettings aSettings, CommandPathPolicy aPolicy) {
        configDir    = aConfigDir.toAbsolutePath();
        agentHome    = aAgentHome.toAbsolutePath();
        agentBinary  = aAgentBinary.toAbsolutePath();
        settings     = aSettings;
        policy       = aPolicy;
        commandRoots = CommandPathPolicy.parseCommandDirs(aSettings.commandDirs());
    }

    SandboxPlan plan() {
        base();
        for (Path file : configFiles()) {
            String     name = file.getFileName().toString();
            JsonObject object;
            try {
                object = CommandConfigFile.read(file);
            } catch (RuntimeException e) {
                plan.problem(name, "cannot be read (" + e.getMessage() + ")");
                continue;
            }
            command(name, object);
        }
        protectedPaths();
        limits();
        return plan;
    }

    // ── base: what every sandboxed agent needs ─────────────────────────────

    private void base() {
        String base = "base";
        for (Path dir : List.of(agentHome, configDir, agentHome.resolve("keys"))) {
            if (Files.isSymbolicLink(dir)) {
                plan.problem(base, dir + " is a link: the agent home, its config and keys must be real directories");
            }
        }
        Path ldso = resolved(base, Path.of("/lib64/ld-linux-x86-64.so.2"));
        plan.add(Kind.RX, safe(base, agentBinary), base, "the agent");
        if (ldso != null) {
            plan.add(Kind.RX, ldso, base, "the dynamic loader");
            plan.add(Kind.RO, ldso.getParent(), base, "shared libraries");
        }
        plan.add(Kind.RO, Path.of("/etc/ld.so.cache"), base, "the loader cache");
        for (String file : List.of("/etc/hosts", "/etc/nsswitch.conf", "/etc/host.conf")) {
            if (Files.exists(Path.of(file))) {
                plan.add(Kind.RO, Path.of(file), base, "\"localhost\" in waitUrl / reloadUrl");
            }
        }
        plan.add(Kind.RO, safe(base, agentHome), base, "the agent home: config, keys");
        plan.add(Kind.RW, safe(base, configDir.resolve(".dc-agent.lock")), base, "the config lock");
        plan.add(Kind.RD, safe(base, Path.of(settings.servicesDir())), base, "the path check lists the services (no file is read)");
        plan.add(Kind.RD, safe(base, Path.of(settings.servicesDefinitionDir())), base, "the path check lists the services (no file is read)");
        plan.add(Kind.RO, Path.of("/proc/self/maps"), base, "the native runtime");
        plan.add(Kind.RO, Path.of("/proc/self/cmdline"), base, "how the agent was started");
        plan.add(Kind.RW, Path.of("/dev/null"), base, "standard streams");
        plan.add(Kind.RO, Path.of("/dev/urandom"), base, "randomness");
        plan.add(Kind.RO, Path.of("/dev/random"), base, "randomness");
        plan.add(Kind.RW, Path.of("/tmp"), base, "upload temp files (the unit's PrivateTmp)");
    }

    // ── commands ───────────────────────────────────────────────────────────

    private void command(String aName, JsonObject aObject) {
        JsonElement typeElement = aObject.get("type");
        String      type        = typeElement != null && typeElement.isJsonPrimitive() ? typeElement.getAsString() : "";
        if (usesGitlabKeys(aObject)) {
            plan.gitlabKeys = true;
        }
        switch (type) {
            case "ZIP_ARCHIVE"   -> writeDir(aName, Gsons.PRETTY_GSON.fromJson(aObject, TZipArchiveConfig.class).getDir(), "ZIP_ARCHIVE dir");
            case "ZIP_DIRS"      -> writeDir(aName, Gsons.PRETTY_GSON.fromJson(aObject, TZipDirsConfig.class).getDir(), "ZIP_DIRS dir");
            case "SAVE_ARTIFACT" -> writeDir(aName, Gsons.PRETTY_GSON.fromJson(aObject, TSaveArtifactConfig.class).getDir(), "SAVE_ARTIFACT dir");
            case "ZIP_ARCHIVE_VERSION" -> zipArchiveVersion(aName, Gsons.PRETTY_GSON.fromJson(aObject, TZipArchiveVersionConfig.class));
            case "JAR", "NODE", "WAR" -> service(aName, type, Gsons.PRETTY_GSON.fromJson(aObject, TJarConfig.class));
            case "FETCH_URL"     -> plan.problem(aName, "FETCH_URL needs outgoing network, which the sandbox does not have");
            case "DOCKER", "DOCKER_CHECK", "DOCKER_PUSH", "SERVICE" ->
                    plan.problem(aName, type + " needs root (containers, service definitions): not for the sandboxed agent");
            default              -> plan.problem(aName, "unknown type '" + type + "'");
        }
    }

    private static boolean usesGitlabKeys(JsonObject aObject) {
        JsonElement keys = aObject.get("keys");
        if (keys == null || !keys.isJsonArray()) {
            return false;
        }
        for (JsonElement key : keys.getAsJsonArray()) {
            if (key.isJsonObject() && key.getAsJsonObject().has("type")
                    && "GITLAB_JWT".equals(key.getAsJsonObject().get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** A directory the command writes throughout. */
    private void writeDir(String aName, String aDir, String aWhy) {
        writeDir(aName, aDir, aWhy, null);
    }

    /** A directory the command writes in: everything (null), itself (empty) or these subtrees. */
    private void writeDir(String aName, String aDir, String aWhy, List<String> aSubtrees) {
        if (!hasText(aDir)) {
            plan.problem(aName, "no dir");
            return;
        }
        Path dir = resolvedWrite(aName, Path.of(aDir));
        if (dir != null) {
            plan.add(Kind.RW, dir, aName, aWhy);
            // several commands in one directory: everything (null) wins, otherwise the subtrees of all
            if (!plan.trees.containsKey(dir)) {
                plan.trees.put(dir, aSubtrees == null ? null : new ArrayList<>(aSubtrees));
            } else if (aSubtrees == null) {
                plan.trees.put(dir, null);
            } else if (plan.trees.get(dir) != null) {
                for (String sub : aSubtrees) {
                    if (!plan.trees.get(dir).contains(sub)) {
                        plan.trees.get(dir).add(sub);
                    }
                }
            }
        }
    }

    private void zipArchiveVersion(String aName, TZipArchiveVersionConfig aConfig) {
        writeDir(aName, aConfig.getDir(), "ZIP_ARCHIVE_VERSION dir");
        if (hasText(aConfig.getDir())) {
            Path dir = resolved(aName, Path.of(aConfig.getDir()));
            if (dir != null && dir.getParent() != null) {
                Path parent = dir.getParent();
                if (BROAD.contains(parent.toString())) {
                    plan.problem(aName, "dir " + aConfig.getDir() + " lies directly in " + parent
                            + ": the agent would have to list it (fsync of the parent) — give it a directory of its own");
                } else {
                    plan.add(Kind.RD, safe(aName, parent), aName, "fsync of the parent of dir (opens it, reads no file)");
                    plan.listed.add(parent);
                }
            }
        }
        url(aName, aConfig.getReloadUrl(), "reloadUrl");
    }

    /** JAR / WAR / NODE: the artifact's directory, and the service it restarts. */
    private void service(String aName, String aType, TJarConfig aConfig) {
        String aArtifact = "WAR".equals(aType) ? aConfig.getWarFilename() : aConfig.getJarFilename();
        if (!hasText(aArtifact)) {
            plan.problem(aName, "no " + ("WAR".equals(aType) ? "warFilename" : "jarFilename"));
            return;
        }
        Path artifactDir = Path.of(aArtifact).getParent();
        if (artifactDir == null) {
            plan.problem(aName, "the artifact has no directory");
            return;
        }
        String       file     = Path.of(aArtifact).getFileName().toString();
        List<String> subtrees = switch (aType) {
            case "NODE" -> List.of("app", "target");                                  // NodeServlet deletes and unpacks them
            case "WAR"  -> List.of(file.endsWith(".war") ? file.substring(0, file.length() - 4) : file); // WarServlet: the exploded war
            default     -> List.of();                                                 // JAR: replaces its file
        };
        writeDir(aName, artifactDir.toString(), aType + " artifact directory"
                + ("NODE".equals(aType) ? " (app/ and target/ are unpacked there)" : "WAR".equals(aType) ? " (the exploded war is deleted there)" : ""),
                subtrees);

        // as AbstractJarServlet: serviceDir or /service/<serviceName>, serviceLogFile or /var/log/<serviceName>/current
        String serviceDir = hasText(aConfig.getServiceDir()) ? aConfig.getServiceDir() : "/service/" + aConfig.getServiceName();
        String logFile    = hasText(aConfig.getServiceLogFile()) ? aConfig.getServiceLogFile()
                : "/var/log/" + aConfig.getServiceName() + "/current";

        Path dir = resolved(aName, Path.of(serviceDir));
        if (dir == null) {
            return;
        }
        Path supervise = resolved(aName, dir.resolve("supervise"));
        if (supervise == null) {
            return;
        }
        if (!Files.isDirectory(supervise, LinkOption.NOFOLLOW_LINKS)) {
            plan.problem(aName, supervise + " is not a directory (is the service supervised?)");
            return;
        }
        for (String fifo : List.of("control", "ok")) {
            Path path = supervise.resolve(fifo);
            try {
                if (!Supervise.isFifo(path)) {
                    plan.problem(aName, path + " is not a fifo");
                    return;
                }
            } catch (IOException e) {
                plan.problem(aName, path + " cannot be inspected (" + e.getMessage() + ")");
                return;
            }
        }
        if (readDirOk(aName, supervise, "supervise")) {
            plan.add(Kind.RO, safe(aName, supervise), aName, "status of " + serviceDir + " (replaced on every change: the directory)");
            plan.add(Kind.RW, safe(aName, supervise.resolve("control")), aName, "stop/start " + serviceDir);
            plan.add(Kind.RW, safe(aName, supervise.resolve("ok")), aName, "is supervise running");
        }

        Path log = resolved(aName, Path.of(logFile));
        Path logDir = log == null ? null : log.getParent();
        Path logRoot = resolved(aName, Path.of(settings.servicesLogDir()));
        if (logDir != null && logRoot != null) {
            if (!logDir.startsWith(logRoot) || logDir.equals(logRoot)) {
                plan.problem(aName, "serviceLogFile " + logFile + " must lie in a directory below " + logRoot
                        + " (the agent would read " + logDir + ")");
            } else if (readDirOk(aName, logDir, "log")) {
                plan.add(Kind.RO, safe(aName, logDir), aName, "the log tail in the answer");
                plan.listed.add(logDir);
            }
        }
        plan.services.add(new SandboxPlan.Service(aName, dir, supervise, logDir, log));
        url(aName, aConfig.getWaitUrl(), "waitUrl");
    }

    /** A loopback URL: its port; HTTPS also the trust store directory. Anything else is refused. */
    private void url(String aName, String aUrl, String aField) {
        if (!hasText(aUrl)) {
            return;
        }
        URI uri;
        try {
            uri = new URI(aUrl);
        } catch (URISyntaxException e) {
            plan.problem(aName, aField + " is not a URL");
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host   = uri.getHost() == null ? "" : uri.getHost();
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            plan.problem(aName, aField + " must be http or https");
            return;
        }
        if (!isLoopback(host)) {
            plan.problem(aName, aField + " goes to " + host + ": the sandbox reaches only this host (localhost)");
            return;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        plan.ports.putIfAbsent(port, aName + " " + aField);
        if ("https".equals(scheme)) {
            trustStore(aName);
        }
    }

    private static boolean isLoopback(String aHost) {
        String host = aHost.startsWith("[") && aHost.endsWith("]") ? aHost.substring(1, aHost.length() - 1) : aHost;
        return "localhost".equalsIgnoreCase(host) || "::1".equals(host) || host.matches("127(\\.\\d{1,3}){3}");
    }

    private void trustStore(String aName) {
        for (Path store : TRUST_STORES) {
            if (Files.exists(store)) {
                Path resolved = resolved(aName, store);
                if (resolved != null && readDirOk(aName, resolved.getParent(), "trust store")) {
                    plan.add(Kind.RO, safe(aName, resolved.getParent()), aName, "the host trust store (HTTPS to localhost)");
                }
                return;
            }
        }
        plan.problem(aName, "HTTPS needs the host trust store, none of " + TRUST_STORES + " exists");
    }

    // ── the path check of the agent: it must reach every protected path ────

    private void protectedPaths() {
        try {
            for (Path path : policy.protectedPaths()) {
                plan.reached.add(safe("base", path)); // other services' paths too: they go into the script
                if (underHome(path)) {
                    plan.protectHome = true;
                }
            }
            envDirs();
        } catch (IOException e) {
            plan.problem("base", "the protected paths cannot be resolved (" + e.getMessage() + ")");
        }
    }

    /**
     * The path check of the agent lists every service's env (and log/env): one reached through a link
     * outside the service roots needs its own --rd (the roots' --rd does not reach it) and r-x.
     */
    private void envDirs() {
        List<Path> roots = new ArrayList<>();
        for (String root : List.of(settings.servicesDir(), settings.servicesDefinitionDir())) {
            Path resolved = resolved("base", Path.of(root));
            if (resolved != null) {
                roots.add(resolved);
            }
        }
        for (String root : List.of(settings.servicesDir(), settings.servicesDefinitionDir())) {
            try (DirectoryStream<Path> services = Files.newDirectoryStream(Path.of(root))) {
                for (Path service : services) {
                    for (String env : List.of("env", "log/env")) {
                        Path dir = service.resolve(env);
                        if (!Files.isDirectory(dir)) {
                            continue;
                        }
                        Path target = resolved("base", dir);
                        if (target != null && roots.stream().noneMatch(target::startsWith)) {
                            if (readDirOk("base", target, "env of " + service.getFileName())) {
                                plan.add(Kind.RD, target, "base", "the path check lists the env of " + service.getFileName());
                                plan.listed.add(target);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // a missing root: the script creates it
            }
        }
    }

    private void limits() {
        int paths = plan.rules().size();
        if (paths > MAX_RULES) {
            plan.problem("base", paths + " paths: the launcher takes at most " + MAX_RULES);
        }
        if (plan.ports.size() > MAX_PORTS) {
            plan.problem("base", plan.ports.size() + " ports: the launcher takes at most " + MAX_PORTS);
        }
    }

    // ── paths ──────────────────────────────────────────────────────────────

    /** Resolved (every link followed); every hop and the path itself checked for ProtectHome. */
    private Path resolved(String aName, Path aPath) {
        if (!aPath.isAbsolute()) {
            plan.problem(aName, aPath + " is not absolute");
            return null;
        }
        List<Path> links = new ArrayList<>();
        Path       resolved;
        try {
            resolved = PathResolver.resolve(aPath, links);
        } catch (IOException e) {
            plan.problem(aName, aPath + " cannot be resolved (" + e.getMessage() + ")");
            return null;
        }
        if (underHome(aPath) || underHome(resolved) || links.stream().anyMatch(SandboxPlanner::underHome)) {
            plan.protectHome = true;
        }
        if (!"base".equals(aName)) {
            for (Path tree : PRIVATE_TREES) {
                if (aPath.startsWith(tree) || resolved.startsWith(tree)) {
                    plan.problem(aName, aPath + " lies in " + tree + ", which is private to the unit (PrivateTmp)");
                    return null;
                }
            }
        }
        for (Path link : links) {
            plan.reached.add(safe(aName, link));
        }
        return safe(aName, resolved);
    }

    /** {@link #resolved} for a path the agent writes: never a broad or protected directory. */
    private Path resolvedWrite(String aName, Path aPath) {
        Path path = resolved(aName, aPath);
        if (path == null) {
            return null;
        }
        if (BROAD.contains(path.toString()) || path.getNameCount() < 2 || commandRoots.contains(path)
                || path.equals(agentHome) || path.startsWith(agentHome) || agentHome.startsWith(path)) {
            plan.problem(aName, path + " is too broad to be written by a command");
            return null;
        }
        return path;
    }

    private boolean readDirOk(String aName, Path aDir, String aWhat) {
        if (aDir == null || BROAD.contains(aDir.toString()) || aDir.getNameCount() < 2 || commandRoots.contains(aDir)) {
            plan.problem(aName, "the " + aWhat + " directory " + aDir + " is too broad to be readable: give it a directory of its own");
            return false;
        }
        return true;
    }

    private Path safe(String aName, Path aPath) {
        if (aPath != null && !SandboxPlan.isProcSelf(aPath) && !SAFE_PATH.matcher(aPath.toString()).matches()) {
            plan.problem(aName, "the path '" + aPath + "' has characters the unit and the script cannot take"
                    + " (allowed: letters, digits and . _ @ + / -)");
        }
        return aPath;
    }

    private static boolean underHome(Path aPath) {
        return HOME_TREES.stream().anyMatch(aPath::startsWith);
    }

    private List<Path> configFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && CommandConfigFile.isConfigFileName(file.getFileName().toString())) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            plan.problem("base", configDir + " cannot be listed (" + e.getMessage() + ")");
        }
        files.sort(null);
        return files;
    }
}
