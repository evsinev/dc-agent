package com.payneteasy.dcagent.controlplane.service.command;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandInfoItem;
import com.payneteasy.dcagent.core.util.gson.GsonReader;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.payneteasy.dcagent.core.util.SafeFiles.listFiles;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * Lists the commands (deployment endpoints) an agent is configured to serve by scanning
 * CONFIG_DIR for {@code *.json} / {@code *.yml} files. The docker command ({@code dc-docker.*})
 * is excluded. The command name is the file name without extension; the type is best-effort
 * (an explicit {@code type} field wins, otherwise it is inferred from present fields). All other
 * config fields are surfaced as display parameters — the secret api-key values are never emitted
 * (see {@link #buildParameters}).
 */
public class CommandListService {

    private static final Logger LOG                = LoggerFactory.getLogger(CommandListService.class);
    private static final String DOCKER_CONFIG_NAME = "dc-docker";
    private static final String TYPE_FIELD         = "type";
    private static final String API_KEYS_FIELD     = "apiKeys";

    private final File       configDir;
    private final GsonReader gsonReader;
    private final YamlParser yamlParser = new YamlParser();

    public CommandListService(File configDir, Gson gson) {
        this.configDir  = configDir;
        this.gsonReader = new GsonReader(gson);
    }

    public List<CommandInfoItem> listCommands() {
        return listFiles(configDir, CommandListService::isCommandConfig)
                .stream()
                .map(this::toCommand)
                .filter(item -> !DOCKER_CONFIG_NAME.equals(item.getName()))
                .sorted(Comparator.comparing(CommandInfoItem::getName))
                .collect(toList());
    }

    private static boolean isCommandConfig(File aFile) {
        String name = aFile.getName();
        return name.endsWith(".json") || name.endsWith(".yml");
    }

    private CommandInfoItem toCommand(File aFile) {
        JsonObject object;
        try {
            object = loadObject(aFile);
        } catch (Exception e) {
            LOG.warn("Cannot read command config {}", aFile.getAbsolutePath(), e);
            object = new JsonObject();
        }

        return CommandInfoItem.builder()
                .name(stripExtension(aFile.getName()))
                .type(detectType(object))
                .parameters(buildParameters(object))
                .build();
    }

    private static String stripExtension(String aFileName) {
        int dot = aFileName.lastIndexOf('.');
        return dot < 0 ? aFileName : aFileName.substring(0, dot);
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

    /**
     * Flattens the config into display parameters. The {@code type} field is skipped (shown
     * separately). The {@code apiKeys} map's keys are secret api-key values, so only its values
     * (owner labels) are exposed — the secrets are never sent to callers.
     */
    private static Map<String, String> buildParameters(JsonObject aObject) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : aObject.entrySet()) {
            String key = entry.getKey();
            if (TYPE_FIELD.equals(key)) {
                continue;
            }
            if (API_KEYS_FIELD.equals(key)) {
                parameters.put(key, ownerLabels(entry.getValue()));
                continue;
            }
            parameters.put(key, stringify(entry.getValue()));
        }
        return parameters;
    }

    private static String ownerLabels(JsonElement aApiKeys) {
        if (!aApiKeys.isJsonObject()) {
            return "";
        }
        return aApiKeys.getAsJsonObject().entrySet().stream()
                .map(entry -> stringify(entry.getValue()))
                .collect(joining(", "));
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

    private JsonObject loadObject(File aFile) {
        if (aFile.getName().endsWith(".json")) {
            return gsonReader.loadJsonObject(aFile);
        }
        return yamlParser.parseFile(aFile, JsonObject.class);
    }
}
