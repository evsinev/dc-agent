package com.payneteasy.dcagent.systemd;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the sandboxed agent needs, derived from its command configs: Landlock rules (with the config
 * each comes from), TCP ports, the services it restarts, and what the host must offer by Unix rights.
 * Built by {@link SandboxPlanner}, printed by {@link DropIn} and {@link HostScript}.
 */
final class SandboxPlan {

    /** Landlock rights of the launcher, from the narrowest. */
    enum Kind {
        /** {@code --rd}: list and open directories beneath, read no file */
        RD,
        /** {@code --ro} */
        RO,
        /** {@code --rx} */
        RX,
        /** {@code --rw}; also ReadWritePaths= and NoExecPaths= */
        RW;

        String option() {
            return "--" + name().toLowerCase(java.util.Locale.ROOT);
        }

        boolean covers(Kind aOther) {
            return switch (this) {
                case RW -> aOther != RX;
                case RX -> aOther != RW;
                case RO -> aOther == RO || aOther == RD;
                case RD -> aOther == RD;
            };
        }
    }

    /** One launcher rule; {@code source} is the config (or "base") it comes from, {@code why} what for. */
    record Rule(Kind kind, Path path, String source, String why) {
    }

    /** A JAR/WAR/NODE service the agent stops and starts. */
    record Service(String source, Path dir, Path supervise, Path logDir, Path logFile) {
    }

    private final Map<String, Rule> rules = new LinkedHashMap<>();
    final Map<Integer, String>      ports = new TreeMap<>();
    final List<Service>             services = new ArrayList<>();
    /**
     * Command directories and what the command writes in them: null — everything (ZIP, SAVE_ARTIFACT);
     * empty — the directory itself (JAR replaces its file); names — the directory and these subtrees
     * (NODE: app, target; WAR: the exploded war). Other entries there are not the agent's business.
     */
    final Map<Path, List<String>>   trees = new LinkedHashMap<>();
    /** Directories the agent must list or open (Unix {@code r-x}). */
    final Set<Path>                 listed = new LinkedHashSet<>();
    /** Paths the agent must reach (every directory above each needs {@code --x}): rules, link hops, protected paths. */
    final Set<Path>                 reached = new LinkedHashSet<>();
    final List<String>              problems = new ArrayList<>();
    boolean                         protectHome;
    boolean                         gitlabKeys;

    /** Adds a rule unless an existing one already covers it (the same path or a directory above it). */
    void add(Kind aKind, Path aPath, String aSource, String aWhy) {
        for (Rule rule : rules.values()) {
            if (rule.kind().covers(aKind) && aPath.startsWith(rule.path()) && !isProcSelf(aPath)) {
                return;
            }
        }
        rules.putIfAbsent(aKind + " " + aPath, new Rule(aKind, aPath, aSource, aWhy));
        reached.add(aPath);
    }

    List<Rule> rules() {
        return List.copyOf(rules.values());
    }

    List<Rule> rules(Kind aKind) {
        return rules.values().stream().filter(rule -> rule.kind() == aKind).toList();
    }

    void problem(String aSource, String aText) {
        problems.add(aSource + ": " + aText);
    }

    static boolean isProcSelf(Path aPath) {
        return aPath.startsWith("/proc/self");
    }
}
