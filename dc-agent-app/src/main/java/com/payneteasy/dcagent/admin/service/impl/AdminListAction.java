package com.payneteasy.dcagent.admin.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.admin.service.messages.TaskListResponse;
import com.payneteasy.dcagent.admin.service.model.TaskListItem;
import com.payneteasy.dcagent.admin.service.model.TaskStateType;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.util.gson.GsonReader;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.core.util.SafeFiles.listFiles;

public class AdminListAction {

    private final File       configDir;
    private final GsonReader gsonReader;

    public AdminListAction(Gson gson, File configDir) {
        this.configDir = configDir;
        gsonReader     = new GsonReader(gson);
    }

    TaskListResponse listTasks() {
//        if(true) {
//            throw new IllegalStateException("test error");
//        }
        return TaskListResponse.builder()
                .items(createItems())
                .build();
    }

    private List<TaskListItem> createItems() {
        return listFiles(configDir, file -> file.getName().endsWith(".json"))
                .stream()
                .map(this::loadFile)
                .sorted(Comparator.comparing(TaskListItem::getTaskName))
                .collect(Collectors.toList());
    }

    private TaskListItem loadFile(File aFile) {
        String name = aFile.getName().replace(".json", "");
        return TaskListItem.builder()
                .taskId( name)
                .taskName(name)
                .taskType(detectType(aFile))
                .description("Description for " + name)
                .runningTime(ThreadLocalRandom.current().nextInt(1, 10) + " min")
                .taskState(TaskStateType.RUNNING)
                .hostname("dev-4.clubber.me")
                .build();
    }

    private TaskType detectType(File aFile) {
        JsonObject object = gsonReader.loadJsonObject(aFile);

        if(object.has("type")) {
            return TaskType.valueOf(object.get("type").getAsString());
        }

        if(object.has("jarFilename")) {
            return TaskType.JAR;
        }

        if(object.has("warFilename")) {
            return TaskType.WAR;
        }

        if(object.has("extension")) {
            return TaskType.SAVE_ARTIFACT;
        }

        if(object.has("dir")) {
            return TaskType.ZIP_ARCHIVE;
        }

        // todo how to detect NODE?

        return TaskType.FETCH_URL;
    }

}
