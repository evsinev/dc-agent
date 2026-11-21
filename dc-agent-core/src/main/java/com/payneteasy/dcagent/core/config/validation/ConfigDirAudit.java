package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks the whole CONFIG_DIR — at start (the agent refuses to start on any problem) and in
 * {@code check-config}:
 * <ul>
 *     <li>the directory exists, is a directory and can be listed — a listing error is a problem,
 *     never an empty directory;</li>
 *     <li>every top-level {@code *.json} / {@code *.yml} is read by {@link CommandConfigFile} and
 *     checked by {@link CommandConfigValidator};</li>
 *     <li>{@code <name>.json} and {@code <name>.yml} together are a problem: the loader would take
 *     the JSON and the YAML would lie there unseen, plaintext keys included.</li>
 * </ul>
 * Other files (no such extension, hidden temporary files) are left alone.
 */
public class ConfigDirAudit {

    private static final String CONFIG_DIR = "CONFIG_DIR ";

    /** An issuer file not refreshed by fetch-jwks for longer than this is reported (a warning, the agent still starts). */
    public static final java.time.Duration STALE_ISSUER = java.time.Duration.ofDays(7);

    private final CommandConfigValidator validator;
    private final Path                   keysDir;
    private final java.time.Clock        clock;

    /** No issuer files checked (tests of other rules). */
    public ConfigDirAudit(CommandConfigValidator aValidator) {
        this(aValidator, null, java.time.Clock.systemUTC());
    }

    /** {@code aKeysDir}: where the issuer files of GITLAB_JWT keys lie; each one a config names must be usable. */
    public ConfigDirAudit(CommandConfigValidator aValidator, Path aKeysDir, java.time.Clock aClock) {
        validator = aValidator;
        keysDir   = aKeysDir;
        clock     = aClock;
    }

    /** Every problem, each prefixed with its file name; empty when the directory is usable. */
    public List<String> audit(Path aConfigDir, ConfigCheckMode aMode) {
        return audit(aConfigDir, aMode, new ArrayList<>());
    }

    /** As {@link #audit(Path, ConfigCheckMode)}; {@code aWarnings} receives the validator's warnings, prefixed. */
    public List<String> audit(Path aConfigDir, ConfigCheckMode aMode, List<String> aWarnings) {
        List<String> problems = new ArrayList<>();
        if (!Files.exists(aConfigDir)) {
            problems.add(CONFIG_DIR + aConfigDir + " does not exist");
            return problems;
        }
        if (!Files.isDirectory(aConfigDir)) {
            problems.add(CONFIG_DIR + aConfigDir + " is not a directory");
            return problems;
        }

        // name -> extensions present, sorted for a stable report
        Map<String, List<Path>> byName = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aConfigDir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (CommandConfigFile.isConfigFileName(fileName)) {
                    byName.computeIfAbsent(CommandConfigFile.commandName(fileName), name -> new ArrayList<>()).add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            problems.add(CONFIG_DIR + aConfigDir + " cannot be listed (" + e.getClass().getSimpleName() + ")");
            return problems;
        }

        for (Map.Entry<String, List<Path>> command : byName.entrySet()) {
            String     name  = command.getKey();
            List<Path> files = command.getValue();
            if (files.size() > 1) {
                problems.add(name + ".json and " + name + ".yml both exist; keep one");
                continue;
            }
            Path file = files.get(0);
            String fileName = file.getFileName().toString();
            JsonObject object;
            try {
                object = CommandConfigFile.read(file);
            } catch (ConfigProblemException e) {
                problems.addAll(e.getProblems());
                continue;
            }
            List<String> warnings = new ArrayList<>();
            List<String> invalid  = validator.validate(name, object, aMode, warnings);
            for (String problem : invalid) {
                problems.add(fileName + ": " + problem);
            }
            if (invalid.isEmpty() && keysDir != null) {
                checkIssuers(fileName, object, problems, warnings);
            }
            for (String warning : warnings) {
                aWarnings.add(fileName + ": " + warning);
            }
        }
        return problems;
    }

    /** Every issuer file the GITLAB_JWT keys of a valid config name: present and usable; fresh — a warning. */
    private void checkIssuers(String aFileName, JsonObject aObject, List<String> aProblems, List<String> aWarnings) {
        if (!aObject.has(CommandKeys.KEYS) || !aObject.get(CommandKeys.KEYS).isJsonArray()) {
            return;
        }
        for (com.google.gson.JsonElement key : aObject.getAsJsonArray(CommandKeys.KEYS)) {
            JsonObject entry = key.getAsJsonObject();
            if (!"GITLAB_JWT".equals(entry.get("type").getAsString())) {
                continue;
            }
            String issuer = entry.get("issuer").getAsString();
            try {
                com.payneteasy.dcagent.core.auth.gitlab.IssuerKeys keys = com.payneteasy.dcagent.core.auth.gitlab.IssuerFiles.load(keysDir, issuer);
                if (keys.fetchedAt() == null) {
                    aWarnings.add("issuer " + issuer + ": no fetchedAt — written by hand; refresh it with fetch-jwks");
                } else if (keys.fetchedAt().isBefore(clock.instant().minus(STALE_ISSUER))) {
                    aWarnings.add("issuer " + issuer + ": fetched " + keys.fetchedAt() + ", more than " + STALE_ISSUER.toDays()
                            + " days ago — is the fetch-jwks timer running?");
                }
                for (java.nio.file.Path path : List.of(keysDir, com.payneteasy.dcagent.core.auth.gitlab.IssuerFiles.path(keysDir, issuer))) {
                    if (writableByOthers(path)) {
                        aWarnings.add("issuer " + issuer + ": " + path + " is writable by group or others — whoever can write it"
                                + " chooses the keys the agent trusts; keep it root's, mode 0755/0644");
                    }
                }
            } catch (com.payneteasy.dcagent.core.auth.gitlab.JwtRejectedException e) {
                aProblems.add(aFileName + ": " + e.getMessage() + " (" + keysDir + ")");
            }
        }
    }

    private static boolean writableByOthers(java.nio.file.Path aPath) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> mode =
                    java.nio.file.Files.getPosixFilePermissions(aPath, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return mode.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                    || mode.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
