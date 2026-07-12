package com.payneteasy.dcagent.controlplane.service.command;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandInfoItem;
import com.payneteasy.dcagent.core.util.gson.GsonReader;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Comparator;
import java.util.List;

import static com.payneteasy.dcagent.core.util.SafeFiles.listFiles;
import static java.util.stream.Collectors.toList;

/**
 * Lists the commands (deployment endpoints) an agent is configured to serve by scanning
 * CONFIG_DIR for {@code *.json} / {@code *.yml} files. The docker command ({@code dc-docker.*})
 * is excluded. The command name is the file name without extension; the type is best-effort
 * (an explicit {@code type} field wins, otherwise it is inferred from present fields).
 */
public class CommandListService {

    private static final Logger LOG                = LoggerFactory.getLogger(CommandListService.class);
    private static final String DOCKER_CONFIG_NAME = "dc-docker";

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
        return CommandInfoItem.builder()
                .name(stripExtension(aFile.getName()))
                .type(detectType(aFile))
                .build();
    }

    private static String stripExtension(String aFileName) {
        int dot = aFileName.lastIndexOf('.');
        return dot < 0 ? aFileName : aFileName.substring(0, dot);
    }

    private TaskType detectType(File aFile) {
        try {
            JsonObject object = loadObject(aFile);
            if (object.has("type")) {
                return TaskType.valueOf(object.get("type").getAsString());
            }
            if (object.has("jarFilename")) {
                return TaskType.JAR;
            }
            if (object.has("warFilename")) {
                return TaskType.WAR;
            }
            if (object.has("extension")) {
                return TaskType.SAVE_ARTIFACT;
            }
            if (object.has("dir")) {
                return TaskType.ZIP_ARCHIVE;
            }
            return TaskType.FETCH_URL;
        } catch (Exception e) {
            LOG.warn("Cannot detect type of command config {}", aFile.getAbsolutePath(), e);
            return null;
        }
    }

    private JsonObject loadObject(File aFile) {
        if (aFile.getName().endsWith(".json")) {
            return gsonReader.loadJsonObject(aFile);
        }
        return yamlParser.parseFile(aFile, JsonObject.class);
    }
}
