package com.payneteasy.dcagent.admin.service.impl;

import com.google.gson.Gson;
import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.admin.service.IUiAdminService;
import com.payneteasy.dcagent.admin.service.messages.*;
import com.payneteasy.dcagent.admin.service.messages.save.FetchUrlConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.JarConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.SaveArtifactConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.ZipArchiveConfigSaveRequest;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.util.GsonReader;
import com.payneteasy.dcagent.util.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;

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
                .jarConfig(new GsonReader(gson)
                        .loadFile(new File(configDir, aRequest.getTaskName() + ".json"), TJarConfig.class)
                        .toBuilder()
                        .apiKeys(Maps.singleMap("gitlab", "***"))
                        .build()
                )
                .build();
    }

    @Override
    public void saveFetchUrl(FetchUrlConfigSaveRequest aRequest) {

    }

    @Override
    public JarConfigSaveRequest saveJar(JarConfigSaveRequest aRequest) {
       return JarConfigSaveRequest.builder().build();
    }

    @Override
    public void saveArtifact(SaveArtifactConfigSaveRequest aRequest) {

    }

    @Override
    public void saveArchive(ZipArchiveConfigSaveRequest aRequest) {

    }

    @Override
    public TokenResponse token(TokenRequest aRequest) {
        return TokenResponse.builder()
                .accessToken(createRandomString("acc"))
                .refreshToken(createRandomString("ref"))
                .build();
    }

    private String createRandomString(String aPrefix) {
        byte[] randomBytes = new byte[64];
        ThreadLocalRandom.current().nextBytes(randomBytes);
        return aPrefix + "-1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes) ;
    }
}
