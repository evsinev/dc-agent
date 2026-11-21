package com.payneteasy.dcagent.core.config.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.model.TKeyType;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import com.payneteasy.dcagent.core.config.validation.CommandConfigFile;
import com.payneteasy.dcagent.core.config.validation.CommandKeys;
import com.payneteasy.dcagent.core.util.AtomicFiles;
import com.payneteasy.dcagent.core.util.gson.Gsons;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * {@code dc-agent migrate-keys}: rewrites the legacy {@code apiKeys} map of every config in
 * CONFIG_DIR as {@code keys} entries of type {@link TKeyType#SHA256} — the same hashes, the owner as
 * the entry's name. Run by hand (CONFIG_DIR is read-only for a sandboxed agent); a config with a
 * plaintext key is not migrated (hash it first). A {@code .yml} becomes {@code <name>.json}, as in
 * {@link ApiKeyMigration}; every write is {@link AtomicFiles}.
 */
public class KeysMigration {

    /** One config to rewrite, or one that cannot be ({@code problem} set). */
    public record Change(Path source, String name, JsonObject original, JsonObject migrated, int keys, String problem) {

        public String report() {
            return source.getFileName() + ": " + (problem != null ? problem
                    : keys + " key(s) apiKeys -> keys" + (source.getFileName().toString().endsWith(".yml") ? ", written as " + name + ".json" : ""));
        }
    }

    /** What would change; nothing is written. Configs already on {@code keys} (or without keys) are left out. */
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
            String fileName = file.getFileName().toString();
            String name     = CommandConfigFile.commandName(fileName);
            if (files.containsKey(name + ".json") && files.containsKey(name + ".yml")) {
                // the agent refuses such a pair; migrating the YAML would overwrite the JSON
                if (fileName.endsWith(".json")) {
                    changes.add(new Change(file, name, null, null, 0, name + ".json and " + name + ".yml both exist; keep one"));
                }
                continue;
            }
            Change change = migrate(file, CommandConfigFile.read(file));
            if (change != null && change.problem() == null
                    && Gsons.PRETTY_GSON.toJson(change.migrated()).getBytes(UTF_8).length > CommandConfigFile.MAX_BYTES) {
                change = new Change(file, name, change.original(), null, 0, "with keys it would be larger than " + CommandConfigFile.MAX_BYTES
                        + " bytes; split it first");
            }
            if (change != null) {
                changes.add(change);
            }
        }
        return changes;
    }

    /** Writes the changes without a problem; each file is durable before the next. */
    public void apply(Path aConfigDir, List<Change> aChanges) throws IOException {
        for (Change change : aChanges) {
            if (change.problem() != null) {
                continue;
            }
            byte[] json   = Gsons.PRETTY_GSON.toJson(change.migrated()).getBytes(UTF_8);
            Path   target = aConfigDir.resolve(change.name() + ".json");
            if (change.source().equals(target)) {
                AtomicFiles.replace(target, json);
            } else {
                AtomicFiles.replace(target, json, change.source());
                Files.delete(change.source());
                AtomicFiles.syncDir(aConfigDir);
            }
        }
    }

    /** The config with apiKeys as keys; null when it has no apiKeys (or already has keys). */
    static Change migrate(Path aSource, JsonObject aObject) {
        String name = CommandConfigFile.commandName(aSource.getFileName().toString());
        JsonElement element = aObject.get(CommandKeys.API_KEYS);
        if (element == null || element.isJsonNull() || CommandKeys.hasKeys(aObject)) {
            return null;
        }
        if (!element.isJsonObject()) {
            return new Change(aSource, name, aObject, null, 0, "\"apiKeys\" is not an object; fix it first");
        }
        JsonArray   keys  = new JsonArray();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!ApiKeyHash.isHashed(entry.getKey())) {
                return new Change(aSource, name, aObject, null, 0, "has a plaintext or malformed key; hash it first (start the agent once, or dc-agent hash-key)");
            }
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                return new Change(aSource, name, aObject, null, 0, "an apiKeys owner is not a string; fix the config first");
            }
            JsonObject key = new JsonObject();
            key.addProperty("name", uniqueName(owner(entry.getValue()), names));
            key.addProperty("type", TKeyType.SHA256.name());
            key.addProperty("hash", entry.getKey());
            keys.add(key);
        }
        JsonObject migrated = new JsonObject();
        for (Map.Entry<String, JsonElement> field : aObject.entrySet()) {
            if (CommandKeys.API_KEYS.equals(field.getKey())) {
                migrated.add(CommandKeys.KEYS, keys);
            } else {
                migrated.add(field.getKey(), field.getValue().deepCopy());
            }
        }
        return new Change(aSource, name, aObject, migrated, keys.size(), null);
    }

    private static String owner(JsonElement aOwner) {
        return aOwner != null && aOwner.isJsonPrimitive() ? aOwner.getAsString() : "";
    }

    /** A valid key name from the owner label: other characters become '-', at most 64, unique ("-2", "-3"). */
    static String uniqueName(String aOwner, Set<String> aTaken) {
        String base = aOwner.replaceAll("[^0-9A-Za-z._-]", "-");
        if (base.isEmpty()) {
            base = "key";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String name = base;
        for (int i = 2; !aTaken.add(name); i++) {
            name = base + "-" + i;
        }
        return name;
    }
}
