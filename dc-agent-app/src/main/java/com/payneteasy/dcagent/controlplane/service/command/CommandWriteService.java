package com.payneteasy.dcagent.controlplane.service.command;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.MaskedApiKey;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey;
import com.payneteasy.dcagent.core.util.Hashes;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.util.gson.GsonReader;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads and writes a single command config file in CONFIG_DIR. Writes are fully typed — the caller
 * hands a finished {@code TXxxConfig} model which is serialized with {@link Gsons#PRETTY_GSON} (no
 * hand-built JSON). API keys are write-only: the client sends {@link ApiKeyOps} (masked ids to keep
 * + new secrets to add), which are merged against the on-disk map. Reads mask each secret to a
 * stable id so it can be echoed back in a later {@code keep}.
 */
public class CommandWriteService {

    private static final Logger LOG            = LoggerFactory.getLogger(CommandWriteService.class);
    private static final String TYPE_FIELD     = "type";
    private static final String API_KEYS_FIELD = "apiKeys";

    public enum Mode { CREATE, UPDATE }

    private final File       configDir;
    private final GsonReader gsonReader;
    private final YamlParser yamlParser = new YamlParser();

    public CommandWriteService(File configDir, Gson gson) {
        this.configDir  = configDir;
        this.gsonReader = new GsonReader(gson);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    /** Full detail (masked keys) for a command, or null when no config file exists. */
    public CommandDetail getCommand(String aName) {
        File file = resolve(aName);
        if (file == null) {
            return null;
        }
        JsonObject object = loadObject(file);
        return CommandDetail.builder()
                .name(aName)
                .type(detectType(object))
                .parameters(parameters(object))
                .apiKeys(maskedKeys(object))
                .build();
    }

    // ── Write ─────────────────────────────────────────────────────────────

    /**
     * Create or update a command. {@code buildModel} receives the merged {@code secret -> owner}
     * map and returns the finished typed config (with type + apiKeys applied). Returns a status of
     * CONFLICT (create over an existing file) or NOT_FOUND (update of a missing file) without
     * writing; otherwise CREATED/UPDATED plus the resulting detail.
     */
    public CommandSaveResult save(TaskType aType, Mode aMode, String aName, ApiKeyOps aOps,
            Function<Map<String, String>, Object> aBuildModel) {

        enforceFixedName(aType, aName);
        File targetJson = SafeFiles.createFileGuarded(configDir, aName + ".json");
        File existing   = resolve(aName);

        if (aMode == Mode.CREATE && existing != null) {
            return new CommandSaveResult(CommandSaveStatus.CONFLICT, null);
        }
        if (aMode == Mode.UPDATE && existing == null) {
            return new CommandSaveResult(CommandSaveStatus.NOT_FOUND, null);
        }

        Map<String, String> mergedKeys = mergeKeys(existing, aOps);
        Object              model      = aBuildModel.apply(mergedKeys);
        String              json       = Gsons.PRETTY_GSON.toJson(model);

        if (aMode == Mode.CREATE) {
            try (OutputStream out = Files.newOutputStream(targetJson.toPath(), StandardOpenOption.CREATE_NEW)) {
                out.write(json.getBytes(UTF_8));
            } catch (FileAlreadyExistsException e) {
                // Lost a create race — treat as a conflict.
                return new CommandSaveResult(CommandSaveStatus.CONFLICT, null);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create command config " + targetJson.getAbsolutePath(), e);
            }
        } else {
            try {
                Files.write(targetJson.toPath(), json.getBytes(UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write command config " + targetJson.getAbsolutePath(), e);
            }
            // A legacy YAML config is migrated to JSON — drop the old file so only one remains.
            if (!existing.equals(targetJson)) {
                SafeFiles.deleteFileWithWarning(existing, "command config migrated to JSON");
            }
        }

        CommandSaveStatus status = aMode == Mode.CREATE ? CommandSaveStatus.CREATED : CommandSaveStatus.UPDATED;
        return new CommandSaveResult(status, getCommand(aName));
    }

    /** Result of a {@link #save} — the status plus (on success) the resulting detail. */
    public static final class CommandSaveResult {
        private final CommandSaveStatus status;
        private final CommandDetail     command;

        CommandSaveResult(CommandSaveStatus status, CommandDetail command) {
            this.status  = status;
            this.command = command;
        }

        public CommandSaveStatus getStatus()  { return status; }
        public CommandDetail     getCommand() { return command; }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void enforceFixedName(TaskType aType, String aName) {
        if (aType == TaskType.FETCH_URL && !"fetch-url".equals(aName)) {
            throw new IllegalArgumentException("FETCH_URL command name must be 'fetch-url'");
        }
        if (aType == TaskType.DOCKER && !"dc-docker".equals(aName)) {
            throw new IllegalArgumentException("DOCKER command name must be 'dc-docker'");
        }
    }

    /** Merge write-only key ops against the on-disk map: keep matched masked ids, then add new. */
    private Map<String, String> mergeKeys(File aExisting, ApiKeyOps aOps) {
        Map<String, String> onDisk = readApiKeys(aExisting);
        Map<String, String> merged = new LinkedHashMap<>();

        Set<String> keep = new HashSet<>(aOps != null && aOps.getKeep() != null ? aOps.getKeep() : List.of());
        for (Map.Entry<String, String> entry : onDisk.entrySet()) {
            if (keep.contains(maskedId(entry.getKey()))) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (aOps != null && aOps.getAdd() != null) {
            for (NewApiKey key : aOps.getAdd()) {
                merged.put(key.getKey(), key.getOwner());
            }
        }
        return merged;
    }

    private Map<String, String> readApiKeys(File aFile) {
        if (aFile == null) {
            return Map.of();
        }
        ApiKeysHolder holder = aFile.getName().endsWith(".json")
                ? gsonReader.loadFile(aFile, ApiKeysHolder.class)
                : yamlParser.parseFile(aFile, ApiKeysHolder.class);
        return holder != null && holder.apiKeys != null ? holder.apiKeys : Map.of();
    }

    /** "****" + first 8 hex chars of SHA-256(secret) — stable and stateless. */
    static String maskedId(String aSecret) {
        byte[] hash = Hashes.sha256(aSecret.getBytes(UTF_8));
        StringBuilder sb = new StringBuilder("****");
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02x", hash[i]));
        }
        return sb.toString();
    }

    private List<MaskedApiKey> maskedKeys(JsonObject aObject) {
        List<MaskedApiKey> keys = new ArrayList<>();
        JsonElement element = aObject.get(API_KEYS_FIELD);
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                keys.add(MaskedApiKey.builder()
                        .maskedId(maskedId(entry.getKey()))
                        .owner(stringify(entry.getValue()))
                        .build());
            }
        }
        return keys;
    }

    private Map<String, String> parameters(JsonObject aObject) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : aObject.entrySet()) {
            String key = entry.getKey();
            if (TYPE_FIELD.equals(key) || API_KEYS_FIELD.equals(key)) {
                continue;
            }
            parameters.put(key, stringify(entry.getValue()));
        }
        return parameters;
    }

    private static TaskType detectType(JsonObject aObject) {
        try {
            if (aObject.has(TYPE_FIELD)) {
                return TaskType.valueOf(aObject.get(TYPE_FIELD).getAsString());
            }
            if (aObject.has("jarFilename")) {
                return TaskType.JAR;
            }
            if (aObject.has("warFilename")) {
                return TaskType.WAR;
            }
            if (aObject.has("extension")) {
                return TaskType.SAVE_ARTIFACT;
            }
            if (aObject.has("dir")) {
                return TaskType.ZIP_ARCHIVE;
            }
            return TaskType.FETCH_URL;
        } catch (Exception e) {
            LOG.warn("Cannot detect command type", e);
            return null;
        }
    }

    private static String stringify(JsonElement aElement) {
        if (aElement.isJsonNull()) {
            return "";
        }
        if (aElement.isJsonPrimitive()) {
            return aElement.getAsString();
        }
        return aElement.toString();
    }

    /** Prefer {@code <name>.json}, then {@code <name>.yml}; null when neither exists. */
    private File resolve(String aName) {
        File json = SafeFiles.createFileGuarded(configDir, aName + ".json");
        if (json.exists()) {
            return json;
        }
        File yml = SafeFiles.createFileGuarded(configDir, aName + ".yml");
        return yml.exists() ? yml : null;
    }

    private JsonObject loadObject(File aFile) {
        if (aFile.getName().endsWith(".json")) {
            return gsonReader.loadJsonObject(aFile);
        }
        return yamlParser.parseFile(aFile, JsonObject.class);
    }

    /** Minimal view used to extract just the api-key map from an on-disk config (typed read). */
    private static final class ApiKeysHolder {
        Map<String, String> apiKeys;
    }
}
