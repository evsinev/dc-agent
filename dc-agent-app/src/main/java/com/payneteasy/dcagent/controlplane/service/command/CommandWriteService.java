package com.payneteasy.dcagent.controlplane.service.command;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.CommandNames;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import com.payneteasy.dcagent.core.config.validation.CommandConfigFile;
import com.payneteasy.dcagent.core.config.validation.ConfigCheckMode;
import com.payneteasy.dcagent.core.config.validation.ConfigProblemException;
import com.payneteasy.dcagent.core.config.validation.CommandConfigValidator;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.MaskedApiKey;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey;
import com.payneteasy.dcagent.core.util.AtomicFiles;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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

    private final File                   configDir;
    private final CommandConfigValidator validator;

    public CommandWriteService(File configDir, Gson gson, CommandConfigValidator aValidator) {
        this.configDir  = configDir;
        this.validator  = aValidator;
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

        validateName(aType, aName);
        // Before the canonical check below: a link out of CONFIG_DIR would fail it with a 500.
        for (String extension : new String[]{".json", ".yml"}) {
            if (Files.isSymbolicLink(configDir.toPath().resolve(aName + extension))) {
                return new CommandSaveResult(CommandSaveStatus.INVALID, null,
                        aName + extension + " is a symbolic link; a config must be a regular file");
            }
        }
        File targetJson = SafeFiles.createFileGuarded(configDir, aName + ".json");
        File existing   = resolve(aName);

        if (Files.exists(configDir.toPath().resolve(aName + ".json")) && Files.exists(configDir.toPath().resolve(aName + ".yml"))) {
            // the agent refuses such a pair; writing the JSON would leave the YAML (and its keys) there
            return new CommandSaveResult(CommandSaveStatus.INVALID, null,
                    aName + ".json and " + aName + ".yml both exist; keep one");
        }
        if (aMode == Mode.CREATE && existing != null) {
            return new CommandSaveResult(CommandSaveStatus.CONFLICT, null);
        }
        if (aMode == Mode.UPDATE && existing == null) {
            return new CommandSaveResult(CommandSaveStatus.NOT_FOUND, null);
        }

        // The existing file is read as the agent reads it (duplicates refused, apiKeys masked in
        // messages) — a raw Gson error would quote a key into the log.
        JsonObject onDisk;
        try {
            onDisk = existing == null ? new JsonObject() : CommandConfigFile.read(existing.toPath());
        } catch (ConfigProblemException e) {
            return new CommandSaveResult(CommandSaveStatus.INVALID, null, String.join("; ", e.getProblems()));
        }

        if (aOps != null && aOps.getAdd() != null) {
            for (NewApiKey key : aOps.getAdd()) {
                if (key.getKey() == null || key.getKey().length() < ApiKeyHash.MIN_KEY_LENGTH) {
                    return new CommandSaveResult(CommandSaveStatus.INVALID, null,
                            "a new api key must be at least " + ApiKeyHash.MIN_KEY_LENGTH + " characters");
                }
            }
        }

        Map<String, String> mergedKeys = mergeKeys(onDisk, aOps);
        Object              model      = aBuildModel.apply(mergedKeys);
        String              json       = Gsons.PRETTY_GSON.toJson(model);

        // The rules the agent applies at start and on a call: a config written here that broke
        // them would answer 401 — and stop the whole agent from starting next time.
        List<String> problems = new ArrayList<>(validator.validate(aName, Gsons.PRETTY_GSON.fromJson(json, JsonObject.class),
                ConfigCheckMode.STRICT));
        if (json.getBytes(UTF_8).length > CommandConfigFile.MAX_BYTES) {
            problems.add("the config is larger than " + CommandConfigFile.MAX_BYTES + " bytes");
        }
        if (!problems.isEmpty()) {
            return new CommandSaveResult(CommandSaveStatus.INVALID, null, String.join("; ", problems));
        }

        // AtomicFiles: never a partial config on disk (a broken file would stop the next start of the
        // whole agent), never readable by others while written, durable once answered
        if (aMode == Mode.CREATE) {
            try {
                if (!AtomicFiles.createNew(targetJson.toPath(), json.getBytes(UTF_8))) {
                    // lost a create race — a conflict, the winner's file stays as it is
                    return new CommandSaveResult(CommandSaveStatus.CONFLICT, null);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create command config " + targetJson.getAbsolutePath(), e);
            }
        } else {
            try {
                // the mode and owner of the file it replaces (a YAML config: of the YAML)
                AtomicFiles.replace(targetJson.toPath(), json.getBytes(UTF_8), existing.toPath());
                // A legacy YAML config is migrated to JSON — drop the old file so only one remains.
                if (!existing.equals(targetJson)) {
                    Files.delete(existing.toPath());
                    AtomicFiles.syncDir(configDir.toPath());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write command config " + targetJson.getAbsolutePath(), e);
            }
        }

        CommandSaveStatus status = aMode == Mode.CREATE ? CommandSaveStatus.CREATED : CommandSaveStatus.UPDATED;
        return new CommandSaveResult(status, getCommand(aName));
    }

    /** Result of a {@link #save} — the status plus (on success) the resulting detail, (on INVALID) why. */
    public static final class CommandSaveResult {
        private final CommandSaveStatus status;
        private final CommandDetail     command;
        private final String            message;

        CommandSaveResult(CommandSaveStatus status, CommandDetail command) {
            this(status, command, null);
        }

        CommandSaveResult(CommandSaveStatus status, CommandDetail command, String message) {
            this.status  = status;
            this.command = command;
            this.message = message;
        }

        public CommandSaveStatus getStatus()  { return status; }
        public CommandDetail     getCommand() { return command; }
        public String            getMessage() { return message; }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void validateName(TaskType aType, String aName) {
        // The same rule the task endpoints apply on a call (CommandAuth) — a command we write is callable.
        if (!CommandNames.isValid(aName)) {
            throw new IllegalArgumentException("Command name must match [0-9a-zA-Z._-]+ without '..'");
        }
        if (aType == TaskType.FETCH_URL && !"fetch-url".equals(aName)) {
            throw new IllegalArgumentException("FETCH_URL command name must be 'fetch-url'");
        }
        if (aType == TaskType.DOCKER && !"dc-docker".equals(aName)) {
            throw new IllegalArgumentException("DOCKER command name must be 'dc-docker'");
        }
    }

    /** Merge write-only key ops against the on-disk map: keep matched masked ids, then add new. */
    private Map<String, String> mergeKeys(JsonObject aExisting, ApiKeyOps aOps) {
        Map<String, String> onDisk = readApiKeys(aExisting);
        Map<String, String> merged = new LinkedHashMap<>();

        Set<String> keep = new HashSet<>(aOps != null && aOps.getKeep() != null ? aOps.getKeep() : List.of());
        for (Map.Entry<String, String> entry : onDisk.entrySet()) {
            if (keep.contains(maskedId(entry.getKey()))) {
                // a kept plaintext key (a config written before hashing) is stored as its hash now;
                // its masked id does not change
                String key = ApiKeyHash.isPlaintext(entry.getKey()) ? ApiKeyHash.hash(entry.getKey()) : entry.getKey();
                merged.put(key, entry.getValue());
            }
        }
        if (aOps != null && aOps.getAdd() != null) {
            for (NewApiKey key : aOps.getAdd()) {
                // stored as its hash only: the config never holds a working key
                merged.put(ApiKeyHash.hash(key.getKey()), key.getOwner());
            }
        }
        return merged;
    }

    /** The {@code key -> owner} entries of an on-disk config; a non-object or a non-string owner is skipped. */
    private static Map<String, String> readApiKeys(JsonObject aObject) {
        Map<String, String> keys    = new LinkedHashMap<>();
        JsonElement         element = aObject.get(API_KEYS_FIELD);
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    keys.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return keys;
    }

    /** "****" + first 8 hex chars of SHA-256(secret) — the same for the secret and its stored hash. */
    static String maskedId(String aEntry) {
        return ApiKeyHash.maskedId(aEntry);
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
            if (aObject.has("versionFile")) {
                return TaskType.ZIP_ARCHIVE_VERSION;
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

    /** As the agent reads it; a refused file is an exception whose message holds no key. */
    private static JsonObject loadObject(File aFile) {
        return CommandConfigFile.read(aFile.toPath());
    }
}
