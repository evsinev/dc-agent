package com.payneteasy.dcagent.systemd;

import com.payneteasy.dcagent.IStartupConfig;
import com.payneteasy.dcagent.core.config.validation.CommandPathPolicy;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code dc-agent systemd-unit [--listen ADDR:PORT] [--slice NAME] [--command-dirs DIRS] [--prepare [--apply] | --check [FILE]]}:
 * the drop-in for the sandboxed agent (stdout), the host preparation (what it would change and the
 * hand-offs; {@code --apply} makes the changes, as root), or whether the installed drop-in still matches
 * the configs. Run outside the sandbox; only {@code --prepare --apply} changes the host.
 * <p>
 * Exit: 0 printed / up to date / nothing to prepare (ready); 1 a config the sandbox cannot serve
 * (reasons on stderr, nothing on stdout) or a preparation step failed; 2 changes or hand-offs pending;
 * 3 the installed drop-in differs (or /etc/dc-agent/env overrides it); 4 none installed; 64 bad usage.
 */
public final class SystemdUnitCommand {

    public static final String NAME = "systemd-unit";

    static final int OK        = 0;
    static final int REFUSED   = 1;
    static final int PENDING   = 2;
    static final int DIFFERENT = 3;
    static final int MISSING   = 4;
    static final int USAGE     = 64;

    private static final Path    ENV_FILE = Path.of("/etc/dc-agent/env");
    private static final Pattern SECRETS  = Pattern.compile("(?i)secret|password|passwd|token|api[_-]?key|private[_-]?key");

    private SystemdUnitCommand() {
    }

    /**
     * @param aValidation the start-up check of the agent for these settings, plus a refusal of any pending
     *                    migration (the configs are read-only in the sandbox): its problems
     * @param aPolicy     the agent's path policy for these settings
     */
    public static int run(String[] aArgs, IStartupConfig aConfig, Path aConfigDir, String aVersion,
                          Function<IStartupConfig, List<String>> aValidation,
                          Function<IStartupConfig, CommandPathPolicy> aPolicy,
                          PrintStream aOut, PrintStream aErr) {
        return run(aArgs, aConfig, aConfigDir, aVersion, aValidation, aPolicy, new HostFacts.Real(), new HostActions.Real(), aOut, aErr);
    }

    static int run(String[] aArgs, IStartupConfig aConfig, Path aConfigDir, String aVersion,
                   Function<IStartupConfig, List<String>> aValidation,
                   Function<IStartupConfig, CommandPathPolicy> aPolicy,
                   HostFacts aFacts, HostActions aActions,
                   PrintStream aOut, PrintStream aErr) {
        String  listen      = null;
        String  slice       = null;
        String  commandDirs = null;
        boolean prepare     = false;
        boolean apply       = false;
        Path    check       = null;
        for (int i = 1; i < aArgs.length; i++) {
            String a = aArgs[i];
            switch (a) {
                case "--listen", "--slice", "--command-dirs" -> {
                    if (i + 1 >= aArgs.length) {
                        return usage(aErr);
                    }
                    String value = aArgs[++i];
                    if ("--listen".equals(a)) {
                        listen = value;
                    } else if ("--slice".equals(a)) {
                        slice = value;
                    } else {
                        commandDirs = value;
                    }
                }
                case "--prepare" -> prepare = true;
                case "--apply" -> apply = true;
                case "--check" -> check = i + 1 < aArgs.length && !aArgs[i + 1].startsWith("--") ? Path.of(aArgs[++i]) : DropIn.INSTALLED;
                default -> {
                    return usage(aErr);
                }
            }
        }
        if ((prepare && check != null) || (apply && !prepare)) {
            return usage(aErr);
        }
        if (listen != null && !listen.matches("(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+]):\\d{1,5}")) {
            aErr.println(NAME + ": --listen needs ADDR:PORT, e.g. 127.0.0.1:8051");
            return USAGE;
        }
        if (slice != null && !slice.matches("[A-Za-z0-9_.-]+\\.slice")) {
            aErr.println(NAME + ": --slice needs NAME.slice");
            return USAGE;
        }

        String installed = null;
        UnitSettings settings;
        if (check != null) {
            if (!Files.isRegularFile(check)) {
                aOut.println(NAME + ": " + check + " is not installed");
                return MISSING;
            }
            try {
                installed = Files.readString(check, StandardCharsets.UTF_8);
            } catch (IOException e) {
                aErr.println(NAME + ": cannot read " + check + " (" + e.getMessage() + ")");
                return REFUSED;
            }
            settings = UnitSettings.parse(installed);
            if (settings == null) {
                aOut.println(NAME + ": " + check + " was not generated by " + NAME);
                return DIFFERENT;
            }
        } else {
            settings = UnitSettings.of(aConfig, commandDirs, listen, slice);
        }

        IStartupConfig config    = settings.apply(aConfig);
        Path           configDir = aConfigDir.toAbsolutePath();
        Path           agentHome = configDir.getParent();
        List<String>   problems  = new ArrayList<>(settings.problems());
        if (problems.isEmpty()) {
            problems.addAll(aValidation.apply(config));
        }
        SandboxPlan    plan      = null;
        if (problems.isEmpty()) {
            plan = new SandboxPlanner(configDir, agentHome, agentHome.resolve("dc-agent"), settings, aPolicy.apply(config)).plan();
            problems.addAll(plan.problems);
        }
        if (!problems.isEmpty()) {
            aErr.println(NAME + ": the sandboxed agent cannot serve " + configDir + " as it is:");
            for (String problem : problems) {
                aErr.println("  - " + problem);
            }
            return REFUSED;
        }

        String dropIn = DropIn.render(plan, settings, configDir, agentHome, aVersion);
        if (prepare) {
            return prepare(plan, settings, configDir, agentHome, apply, aFacts, aActions, aOut);
        }
        if (check == null) {
            aOut.print(dropIn);
            return OK;
        }
        List<String> overrides = envOverrides();
        if (!overrides.isEmpty()) {
            aOut.println(NAME + ": " + ENV_FILE + " sets " + String.join(", ", overrides)
                    + " — it overrides the drop-in (only " + UnitSettings.SECRET_VARIABLES + " belong there)");
            return DIFFERENT;
        }
        if (DropIn.body(dropIn).equals(DropIn.body(installed))) {
            aOut.println(NAME + ": " + check + " is up to date");
            return OK;
        }
        aOut.println(NAME + ": " + check + " differs from what the configs need — regenerate it:");
        diff(DropIn.body(installed), DropIn.body(dropIn), aOut);
        return DIFFERENT;
    }

    private static int prepare(SandboxPlan aPlan, UnitSettings aSettings, Path aConfigDir, Path aAgentHome, boolean aApply,
                               HostFacts aFacts, HostActions aActions, PrintStream aOut) {
        HostPreparation           preparation = new HostPreparation(aPlan, aSettings, aConfigDir, aAgentHome, aFacts);
        List<HostPreparation.Step> steps      = preparation.decide();
        HostPreparation.print(steps, notes(aSettings), aOut);
        boolean handoffs = steps.stream().anyMatch(HostPreparation.Step::handoff);
        if (!aApply) {
            if (steps.stream().anyMatch(step -> !step.handoff())) {
                aOut.println("Run with --apply (as root) to make the changes.");
            }
            return steps.isEmpty() ? OK : PENDING;
        }
        if (!"root".equals(System.getProperty("user.name"))) {
            aOut.println(NAME + ": --apply needs root");
            return REFUSED;
        }
        if (!HostPreparation.apply(steps, aActions, aOut)) {
            return REFUSED;
        }
        List<String> problems = preparation.verify();
        if (aFacts instanceof HostFacts.Real) {
            problems.addAll(checkConfigAsAgent(aSettings, aAgentHome));
        }
        for (String problem : problems) {
            aOut.println("  NOT READY " + problem);
        }
        if (!problems.isEmpty()) {
            return REFUSED;
        }
        if (handoffs) {
            aOut.println("Hand-offs left: do them, then run --prepare again.");
            return PENDING;
        }
        aOut.println("Ready.");
        return OK;
    }

    /** check-config as the agent, with the service's environment: any Unix-rights gap of the start-up check shows here. */
    private static List<String> checkConfigAsAgent(UnitSettings aSettings, Path aAgentHome) {
        List<String> command = new ArrayList<>(List.of("runuser", "-u", HostPreparation.USER, "--", "env"));
        aSettings.environment().forEach((name, value) -> command.add(name + "=" + value));
        command.add(aAgentHome.resolve("dc-agent").toString());
        command.add("check-config");
        HostFacts.Real.Result result = HostFacts.Real.execute(command);
        return result.code() == 0 ? List.of() : List.of("check-config as " + HostPreparation.USER + " fails: " + String.join(" | ",
                result.lines().stream().filter(l -> !l.contains("ersInvocationHandler")).toList()));
    }

    /** For check-config: a warning when an installed drop-in no longer matches; null when fine or none. */
    public static String staleWarning(IStartupConfig aConfig, Path aConfigDir, String aVersion,
                                      Function<IStartupConfig, List<String>> aValidation,
                                      Function<IStartupConfig, CommandPathPolicy> aPolicy) {
        if (!Files.isReadable(DropIn.INSTALLED)) {
            return null;
        }
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        PrintStream                   out  = new PrintStream(sink, true, StandardCharsets.UTF_8);
        int code = run(new String[]{NAME, "--check"}, aConfig, aConfigDir, aVersion, aValidation, aPolicy, out, out);
        return code == OK ? null : DropIn.INSTALLED + " is stale: run " + NAME + " (" + NAME + " --check shows why)";
    }

    private static List<String> envOverrides() {
        List<String> names = new ArrayList<>();
        if (!Files.isReadable(ENV_FILE)) {
            return names;
        }
        try {
            for (String line : Files.readAllLines(ENV_FILE, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                String name = trimmed.substring(0, trimmed.indexOf('=')).trim();
                if (!UnitSettings.SECRET_VARIABLES.contains(name)) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            names.add("(unreadable: " + e.getMessage() + ")");
        }
        return names;
    }

    /** For --prepare: run scripts other users can read that look like they hold secrets. */
    static List<String> notes(UnitSettings aSettings) {
        List<String> notes = new ArrayList<>();
        for (String root : List.of(aSettings.servicesDefinitionDir(), aSettings.servicesDir())) {
            try (DirectoryStream<Path> services = Files.newDirectoryStream(Path.of(root))) {
                for (Path service : services) {
                    for (Path run : List.of(service.resolve("run"), service.resolve("log/run"))) {
                        if (readableByOthers(run) && looksSecret(run)) {
                            Path real = run.toRealPath();
                            String note = real + " is readable by every local user and looks like it holds a secret: chmod 0700 '" + real + "'";
                            if (!notes.contains(note)) {
                                notes.add(note);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // no such root: nothing to note
            }
        }
        notes.add("the agent never reads run scripts (--rd on the service roots); other local users can");
        return notes.size() == 1 ? List.of() : notes;
    }

    /** Readable by any local user: the file itself, and every directory on the way passable by others. */
    private static boolean readableByOthers(Path aFile) {
        try {
            Path real = aFile.toRealPath();
            if (!Files.isRegularFile(real) || !Files.getPosixFilePermissions(real).contains(PosixFilePermission.OTHERS_READ)) {
                return false;
            }
            for (Path dir = real.getParent(); dir != null; dir = dir.getParent()) {
                if (!Files.getPosixFilePermissions(dir).contains(PosixFilePermission.OTHERS_EXECUTE)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static boolean looksSecret(Path aFile) {
        try {
            return Files.size(aFile) < 1_000_000 && SECRETS.matcher(Files.readString(aFile, StandardCharsets.ISO_8859_1)).find();
        } catch (IOException e) {
            return false;
        }
    }

    /** A plain line diff: what is installed (-) and what the configs need (+). */
    static void diff(String aInstalled, String aWanted, PrintStream aOut) {
        List<String> have = List.of(aInstalled.split("\n"));
        List<String> want = List.of(aWanted.split("\n"));
        for (String line : have) {
            if (!want.contains(line)) {
                aOut.println("- " + line);
            }
        }
        for (String line : want) {
            if (!have.contains(line)) {
                aOut.println("+ " + line);
            }
        }
    }

    private static int usage(PrintStream aErr) {
        aErr.println("usage: dc-agent " + NAME + " [--listen ADDR:PORT] [--slice NAME.slice] [--command-dirs DIRS] [--prepare [--apply] | --check [FILE]]");
        aErr.println("  prints the systemd drop-in for the sandboxed agent (" + DropIn.INSTALLED + ")");
        aErr.println("  --prepare      lists what the host needs (changes it can make safely, hand-offs for a human); changes nothing");
        aErr.println("  --apply        with --prepare, as root: makes those changes, then checks everything as the agent");
        aErr.println("  --check        compares the installed drop-in with what the configs need (exit 0 same, 3 differs, 4 missing)");
        return USAGE;
    }
}
