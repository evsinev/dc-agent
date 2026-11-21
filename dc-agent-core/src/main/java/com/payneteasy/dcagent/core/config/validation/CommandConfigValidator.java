package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.CommandNames;
import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.modules.zipversion.ConfigValueException;
import com.payneteasy.dcagent.core.modules.zipversion.ZipArchiveVersionSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules a command config must pass to be used — the same at start ({@link ConfigDirAudit}),
 * in {@code check-config}, before the control plane writes it and on every request
 * ({@code ConfigServiceImpl}):
 * <ol>
 *     <li>the name is a {@link CommandNames} name; {@code FETCH_URL} only as {@code fetch-url},
 *     {@code DOCKER} only as {@code dc-docker};</li>
 *     <li>{@code type} is present and is one an agent endpoint serves — never inferred from the other
 *     fields (that would make a dropped-in config without a type valid);</li>
 *     <li>{@code keys} ({@link CommandKeys}, strict) or the legacy {@code apiKeys} — never both;
 *     {@code apiKeys} is a non-empty object of strings; every key is {@code sha256:<hex>}
 *     ({@link ApiKeyHash}), a plaintext one counted by the {@link ConfigCheckMode};</li>
 *     <li>the object reads into the model of its type with the same Gson the loader uses;</li>
 *     <li>{@code ZIP_ARCHIVE_VERSION}: {@link ZipArchiveVersionSettings#from} accepts it.</li>
 * </ol>
 * Problems name the field, never a value.
 */
public class CommandConfigValidator {

    public static final String FETCH_URL_NAME = "fetch-url";
    public static final String DOCKER_NAME    = "dc-docker";

    private static final String TYPE     = "type";
    private static final String API_KEYS = "apiKeys";

    /** The types an agent endpoint serves, with the model it reads the config into. */
    private static final Map<TaskType, Class<?>> MODELS = createModels();

    private static final Pattern GSON_PATH = Pattern.compile("path (\\$\\S*)");

    private final Gson              gson;
    private final Duration          idleTimeout;
    private final CommandPathPolicy paths;

    /**
     * @param aGson        the Gson the config loader reads models with
     * @param aIdleTimeout the agent's {@code WEB_SERVER_IDLE_TIMEOUT} — {@code zip-archive-version}
     *                     settings are checked against it
     * @param aPaths       where a command may write
     */
    public CommandConfigValidator(Gson aGson, Duration aIdleTimeout, CommandPathPolicy aPaths) {
        gson        = aGson;
        idleTimeout = aIdleTimeout;
        paths       = aPaths;
    }

    public static boolean isEndpointType(TaskType aType) {
        return MODELS.containsKey(aType);
    }

    /** The problems of {@code aObject} as the config of command {@code aName}; empty when it is usable. */
    public List<String> validate(String aName, JsonObject aObject, ConfigCheckMode aMode) {
        return validate(aName, aObject, aMode, new ArrayList<>());
    }

    /**
     * As {@link #validate(String, JsonObject, ConfigCheckMode)}; {@code aWarnings} receives what is
     * valid but probably wider than meant (a GitLab rule without ref_protected, …) — check-config shows it.
     */
    public List<String> validate(String aName, JsonObject aObject, ConfigCheckMode aMode, List<String> aWarnings) {
        List<String> problems = new ArrayList<>();

        if (!CommandNames.isValid(aName)) {
            problems.add("the command name must match [0-9a-zA-Z._-]+ without '..'");
        }

        TaskType type = type(aObject, problems);
        if (type == TaskType.FETCH_URL && !FETCH_URL_NAME.equals(aName)) {
            problems.add("type FETCH_URL is only valid in " + FETCH_URL_NAME + ".json/.yml");
        }
        if (type == TaskType.DOCKER && !DOCKER_NAME.equals(aName)) {
            problems.add("type DOCKER is only valid in " + DOCKER_NAME + ".json/.yml");
        }

        int     before  = problems.size();
        boolean typed   = CommandKeys.check(aObject, problems, aWarnings);
        if (!typed) {
            checkApiKeys(aObject, aMode, problems);
        }
        // keys already reported: the rest of the config is still read (paths), without them, so one
        // mistake is not reported twice
        JsonObject forModel = aObject;
        if (typed && problems.size() > before) {
            forModel = aObject.deepCopy();
            forModel.remove(CommandKeys.KEYS);
        }

        if (type != null) {
            Object model = readModel(type, forModel, problems);
            if (model != null) {
                checkPaths(type, model, problems);
            }
            if (model instanceof TZipArchiveVersionConfig config) {
                try {
                    ZipArchiveVersionSettings.from(aName, config, idleTimeout);
                } catch (ConfigValueException e) {
                    problems.add(e.getMessage());
                }
            }
        }
        return problems;
    }

    /** Where the command writes: {@code dir}, or the directory of the jar/war, and the service it restarts. */
    private void checkPaths(TaskType aType, Object aModel, List<String> aProblems) {
        switch (aType) {
            case ZIP_ARCHIVE         -> aProblems.addAll(paths.checkDirectory("dir", ((TZipArchiveConfig) aModel).getDir()));
            case ZIP_DIRS            -> aProblems.addAll(paths.checkDirectory("dir", ((TZipDirsConfig) aModel).getDir()));
            case ZIP_ARCHIVE_VERSION -> aProblems.addAll(paths.checkDirectory("dir", ((TZipArchiveVersionConfig) aModel).getDir()));
            case SAVE_ARTIFACT       -> aProblems.addAll(paths.checkDirectory("dir", ((TSaveArtifactConfig) aModel).getDir()));
            case JAR, NODE           -> checkJar("jarFilename", ((TJarConfig) aModel).getJarFilename(), (TJarConfig) aModel, aProblems);
            case WAR                 -> checkJar("warFilename", ((TJarConfig) aModel).getWarFilename(), (TJarConfig) aModel, aProblems);
            default                  -> { /* FETCH_URL, DOCKER: no path of their own */ }
        }
    }

    private void checkJar(String aField, String aFile, TJarConfig aConfig, List<String> aProblems) {
        if (aFile == null || aFile.isBlank()) {
            aProblems.add("\"" + aField + "\" is required");
        } else {
            java.nio.file.Path parent;
            try {
                parent = java.nio.file.Path.of(aFile).getParent();
            } catch (java.nio.file.InvalidPathException e) {
                parent = null;
            }
            // the jar is replaced there, and NODE also unpacks and deletes app/ and target/ there
            aProblems.addAll(parent == null
                    ? List.of("\"" + aField + "\" must be an absolute file path")
                    : paths.checkDirectory(aField + "'s directory", parent.toString()));
        }
        // the same default as AbstractJarServlet (a blank value counts as none)
        String serviceDir = null;
        if (hasText(aConfig.getServiceDir())) {
            serviceDir = aConfig.getServiceDir();
        } else if (hasText(aConfig.getServiceName())) {
            serviceDir = "/service/" + aConfig.getServiceName();
        }
        if (serviceDir == null) {
            aProblems.add("\"serviceName\" or \"serviceDir\" is required");
        } else {
            aProblems.addAll(paths.checkServiceDir(serviceDir));
        }
    }

    /** The servlet's own test ({@code AbstractJarServlet.getDefault}), so both pick the same serviceDir. */
    private static boolean hasText(String aValue) {
        return com.payneteasy.dcagent.core.util.Strings.hasText(aValue);
    }

    private static TaskType type(JsonObject aObject, List<String> aProblems) {
        JsonElement element = aObject.get(TYPE);
        if (element == null || element.isJsonNull()) {
            aProblems.add("no \"type\"; add the type of the command, e.g. \"type\": \"ZIP_ARCHIVE\" (it is never guessed)");
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            aProblems.add("\"type\" must be a string");
            return null;
        }
        String   value = element.getAsString();
        TaskType type;
        try {
            type = TaskType.valueOf(value);
        } catch (IllegalArgumentException e) {
            aProblems.add("unknown \"type\"; expected one of " + MODELS.keySet());
            return null;
        }
        if (!isEndpointType(type)) {
            aProblems.add("\"type\": " + type + " is not served by any agent endpoint; expected one of " + MODELS.keySet());
            return null;
        }
        return type;
    }

    private static void checkApiKeys(JsonObject aObject, ConfigCheckMode aMode, List<String> aProblems) {
        JsonElement element = aObject.get(API_KEYS);
        if (element == null || element.isJsonNull()) {
            aProblems.add("no \"keys\" (or the legacy \"apiKeys\")");
            return;
        }
        if (!element.isJsonObject()) {
            aProblems.add("\"apiKeys\" must be an object of key -> owner");
            return;
        }
        JsonObject keys = element.getAsJsonObject();
        if (keys.isEmpty()) {
            aProblems.add("\"apiKeys\" is empty");
            return;
        }
        int malformed = 0;
        int plaintext = 0;
        for (Map.Entry<String, JsonElement> entry : keys.entrySet()) {
            JsonElement owner = entry.getValue();
            if (!owner.isJsonPrimitive() || !owner.getAsJsonPrimitive().isString()) {
                aProblems.add("\"apiKeys\": every owner must be a string");
                return;
            }
            String key = entry.getKey();
            if (ApiKeyHash.hasReservedPrefix(key) && !ApiKeyHash.isHashed(key)) {
                malformed++;
            } else if (ApiKeyHash.isPlaintext(key)) {
                plaintext++;
            }
        }
        if (malformed > 0) {
            // reserved: never hashed again, never taken as a key — a hand-written hash gone wrong
            aProblems.add("\"apiKeys\": " + malformed + " entr" + (malformed == 1 ? "y starts" : "ies start")
                    + " with sha256: but " + (malformed == 1 ? "is" : "are") + " not sha256: + 64 lowercase hex");
        }
        if (plaintext > 0 && aMode == ConfigCheckMode.STRICT) {
            aProblems.add("\"apiKeys\": " + plaintext + " plaintext key(s); a key is stored as sha256:<hex>"
                    + " (java -jar dc-agent.jar hash-key)");
        }
    }

    private Object readModel(TaskType aType, JsonObject aObject, List<String> aProblems) {
        try {
            return gson.fromJson(aObject, MODELS.get(aType));
        } catch (RuntimeException e) {
            aProblems.add("the fields do not fit a " + aType + " config (" + e.getClass().getSimpleName() + gsonPath(e) + ")");
            return null;
        }
    }

    /** Only the JSON path of a Gson error (masked inside apiKeys) — the message itself may quote a value. */
    private static String gsonPath(Throwable aError) {
        for (Throwable error = aError; error != null; error = error.getCause()) {
            Matcher matcher = GSON_PATH.matcher(String.valueOf(error.getMessage()));
            if (matcher.find()) {
                return " at " + CommandConfigFile.maskGsonLocation(matcher.group(1));
            }
        }
        return "";
    }

    private static Map<TaskType, Class<?>> createModels() {
        Map<TaskType, Class<?>> models = new EnumMap<>(TaskType.class);
        models.put(TaskType.ZIP_ARCHIVE        , TZipArchiveConfig.class);
        models.put(TaskType.ZIP_DIRS           , TZipDirsConfig.class);
        models.put(TaskType.ZIP_ARCHIVE_VERSION, TZipArchiveVersionConfig.class);
        models.put(TaskType.FETCH_URL          , TFetchUrlConfig.class);
        models.put(TaskType.SAVE_ARTIFACT      , TSaveArtifactConfig.class);
        models.put(TaskType.JAR                , TJarConfig.class);
        models.put(TaskType.WAR                , TJarConfig.class);
        models.put(TaskType.NODE               , TJarConfig.class);
        models.put(TaskType.DOCKER             , TDockerConfig.class);
        return Collections.unmodifiableMap(models);
    }
}
