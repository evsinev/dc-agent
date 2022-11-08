package com.payneteasy.dcagent.admin.service.impl;

import com.google.gson.Gson;
import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.admin.service.IUiAdminService;
import com.payneteasy.dcagent.admin.service.messages.TaskListResponse;
import com.payneteasy.dcagent.admin.service.messages.TaskViewRequest;
import com.payneteasy.dcagent.admin.service.messages.TaskViewJarResponse;
import com.payneteasy.dcagent.admin.service.messages.save.FetchUrlConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.JarConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.SaveArtifactConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.ZipArchiveConfigSaveRequest;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.util.GsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class UiAdminServiceImpl implements IUiAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(UiAdminServiceImpl.class);

    private final Gson            gson;
    private final File            configDir;
    private final File            optDir;
    private final AdminListAction adminListAction;

    public UiAdminServiceImpl(Gson aGson, File aConfigDir, File aOptDir) {
        gson            = aGson;
        configDir       = aConfigDir;
        optDir          = aOptDir;
        adminListAction = new AdminListAction(aGson, aConfigDir);
    }

    @Override
    public TaskListResponse listTasks(VoidRequest aVoid) {
        return adminListAction.listTasks();
    }

    @Override
    public TaskViewJarResponse getJarTask(TaskViewRequest aRequest) {
        return TaskViewJarResponse.builder()
                .taskName(aRequest.getTaskName())
                .jarConfig(new GsonReader(gson).loadFile(new File(configDir, aRequest.getTaskName() + ".json"), TJarConfig.class))
                .build();
    }

    @Override
    public void saveFetchUrl(FetchUrlConfigSaveRequest aRequest) {

    }

    @Override
    public void saveJar(JarConfigSaveRequest aRequest) {

    }

    @Override
    public void saveArtifact(SaveArtifactConfigSaveRequest aRequest) {

    }

    @Override
    public void saveArchive(ZipArchiveConfigSaveRequest aRequest) {

    }
}
