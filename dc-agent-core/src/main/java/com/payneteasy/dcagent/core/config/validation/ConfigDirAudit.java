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

    private final CommandConfigValidator validator;

    public ConfigDirAudit(CommandConfigValidator aValidator) {
        validator = aValidator;
    }

    /** Every problem, each prefixed with its file name; empty when the directory is usable. */
    public List<String> audit(Path aConfigDir) {
        List<String> problems = new ArrayList<>();
        if (!Files.exists(aConfigDir)) {
            problems.add("CONFIG_DIR " + aConfigDir + " does not exist");
            return problems;
        }
        if (!Files.isDirectory(aConfigDir)) {
            problems.add("CONFIG_DIR " + aConfigDir + " is not a directory");
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
            problems.add("CONFIG_DIR " + aConfigDir + " cannot be listed (" + e.getClass().getSimpleName() + ")");
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
            for (String problem : validator.validate(name, object)) {
                problems.add(fileName + ": " + problem);
            }
        }
        return problems;
    }
}
