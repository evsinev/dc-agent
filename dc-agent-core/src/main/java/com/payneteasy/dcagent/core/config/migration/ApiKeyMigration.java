package com.payneteasy.dcagent.core.config.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import com.payneteasy.dcagent.core.config.validation.CommandConfigFile;
import com.payneteasy.dcagent.core.util.AtomicFiles;
import com.payneteasy.dcagent.core.util.gson.Gsons;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Hashes the plaintext api keys of CONFIG_DIR at start ({@link ApiKeyHash}). Runs after the directory
 * passed the pre-migration check, so every config here is a readable regular file, no name has both a
 * {@code .json} and a {@code .yml}, and no entry has a malformed {@code sha256:} prefix.
 * <p>
 * Running it again changes nothing: only entries outside the reserved prefix are hashed, a hash is
 * never hashed again. A {@code .json} is rewritten in place; a {@code .yml} becomes {@code <name>.json}
 * (JSON from the converted YAML, as the control plane does on update) and the YAML is removed. Every
 * write is {@link AtomicFiles}: no partial file, no window where the new file is readable by others,
 * no backup with the old keys.
 */
public class ApiKeyMigration {

    private static final String API_KEYS = "apiKeys";

    /** One config to rewrite: the object with its keys hashed, and what changed. */
    public record Change(Path source, String name, JsonObject migrated, int hashed, int shortKeys) {

        public String report() {
            return source.getFileName() + ": " + hashed + " plaintext key(s) hashed"
                    + (source.getFileName().toString().endsWith(".yml") ? ", written as " + name + ".json" : "")
                    + (shortKeys > 0 ? "; " + shortKeys + " of them shorter than " + ApiKeyHash.MIN_KEY_LENGTH
                    + " characters — replace them with longer keys" : "");
        }
    }

    /** What the migration would do; nothing is written. */
    public List<Change> plan(Path aConfigDir) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aConfigDir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (CommandConfigFile.isConfigFileName(fileName)) {
                    files.put(fileName, entry);
                }
            }
        }
        List<Change> changes = new ArrayList<>();
        for (Path file : files.values()) {
            Change change = migrate(file, CommandConfigFile.read(file));
            if (change != null) {
                changes.add(change);
            }
        }
        return changes;
    }

    /**
     * What the planned files would break before anything is written: a hash is longer than many keys,
     * so a large config may outgrow {@link CommandConfigFile#MAX_BYTES} — the agent would then refuse
     * the file it has just written.
     */
    public List<String> problems(List<Change> aChanges) {
        List<String> problems = new ArrayList<>();
        for (Change change : aChanges) {
            int size = bytes(change).length;
            if (size > CommandConfigFile.MAX_BYTES) {
                problems.add(change.source().getFileName() + ": with its keys hashed it would be " + size
                        + " bytes, more than " + CommandConfigFile.MAX_BYTES + "; split it or hash the keys by hand");
            }
        }
        return problems;
    }

    /** Writes the changes; each file is done (and durable) before the next. {@link #problems} must be empty. */
    public void apply(Path aConfigDir, List<Change> aChanges) throws IOException {
        if (!problems(aChanges).isEmpty()) {
            throw new IllegalStateException("the migration would write a config the agent refuses: " + problems(aChanges));
        }
        for (Change change : aChanges) {
            byte[] json   = bytes(change);
            Path   target = aConfigDir.resolve(change.name() + ".json");
            if (change.source().equals(target)) {
                AtomicFiles.replace(target, json);
            } else {
                // YAML → JSON: the JSON first (durable), then the YAML goes; a crash in between leaves
                // both, which the next start refuses with a clear reason instead of guessing
                AtomicFiles.replace(target, json, change.source());
                Files.delete(change.source());
                AtomicFiles.syncDir(aConfigDir);
            }
        }
    }

    private static byte[] bytes(Change aChange) {
        return Gsons.PRETTY_GSON.toJson(aChange.migrated()).getBytes(UTF_8);
    }

    /** The config with its plaintext keys hashed, or null when it has none. */
    static Change migrate(Path aSource, JsonObject aObject) {
        JsonElement element = aObject.get(API_KEYS);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject keys      = element.getAsJsonObject();
        JsonObject hashed    = new JsonObject();
        int        plaintext = 0;
        int        shortKeys = 0;
        for (Map.Entry<String, JsonElement> entry : keys.entrySet()) {
            String key = entry.getKey();
            if (ApiKeyHash.isPlaintext(key)) {
                plaintext++;
                if (key.length() < ApiKeyHash.MIN_KEY_LENGTH) {
                    shortKeys++;
                }
                key = ApiKeyHash.hash(key);
            }
            if (!hashed.has(key)) { // the same key twice (plaintext and hashed): one entry, the first owner
                hashed.add(key, entry.getValue());
            }
        }
        if (plaintext == 0) {
            return null;
        }
        JsonObject migrated = new JsonObject();
        for (Map.Entry<String, JsonElement> field : aObject.entrySet()) {
            migrated.add(field.getKey(), API_KEYS.equals(field.getKey()) ? hashed : field.getValue().deepCopy());
        }
        String fileName = aSource.getFileName().toString();
        return new Change(aSource, CommandConfigFile.commandName(fileName), migrated, plaintext, shortKeys);
    }
}
