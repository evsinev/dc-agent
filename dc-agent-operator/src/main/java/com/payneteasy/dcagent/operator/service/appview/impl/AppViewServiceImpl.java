package com.payneteasy.dcagent.operator.service.appview.impl;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.exception.DcProblem;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.SendTaskParam;
import com.payneteasy.dcagent.core.task.send.SendTaskResult;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.model.TApp;
import com.payneteasy.dcagent.operator.service.appview.AppViewRequest;
import com.payneteasy.dcagent.operator.service.appview.AppViewResult;
import com.payneteasy.dcagent.operator.service.appview.IAppViewService;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.taskcreate.ITaskCreateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;
import static java.nio.file.Files.readAllBytes;

public class AppViewServiceImpl implements IAppViewService {

    private static final Logger LOG = LoggerFactory.getLogger( AppViewServiceImpl.class );

    private final ISendTaskService       sendTaskService;
    private final IOperatorConfigService configService;
    private final IAppService            appService;
    private final ITaskCreateService     taskCreateService;

    public AppViewServiceImpl(ISendTaskService sendTaskService, IOperatorConfigService configService, IAppService appService, ITaskCreateService taskCreateService) {
        this.sendTaskService   = sendTaskService;
        this.configService     = configService;
        this.appService        = appService;
        this.taskCreateService = taskCreateService;
    }

    @Override
    public AppViewResult viewApp(AppViewRequest aRequest) {
        TApp app = appService.getApp(aRequest.getAppName());

        try(TempFile taskFile = taskCreateService.createTaskZipFile(app.getTaskName())) {
            String         taskName        = app.getTaskName();
            SendTaskResult checkTaskResult = getCheckTaskText(taskName, app.getTaskHost(), taskFile.getFile());
            return AppViewResult.builder()
                    .appName        (aRequest.getAppName())
                    .taskCheckText  (checkTaskResult.getText())
                    .taskCheckColor (checkTaskResult.getStatusCode() == 200 ? "black" : "red-600")
                    .taskName       (taskName)
                    .taskType       (app.getTaskType())
                    .taskHost       (app.getTaskHost())
                    .agentUrl( configService.findAgentHost(app.getTaskHost()).orElse(TAgentHost.builder().url("no agent url").build()).getUrl())
                    .build();
        } catch (IOException e) {
            throw problem(DcProblem.CANNOT_CREATE_TASK_ZIP_FILE)
                    .exception(e);
        }
    }

    private SendTaskResult getCheckTaskText(String aTaskName, String aHost, File aTaskFile) {
        try {
            TAgentHost agent = configService.findAgentHost(aHost).orElseThrow(() -> new IllegalArgumentException("No config for host " + aHost));

            return sendTaskService.sendTask(SendTaskParam.builder()
                    .taskName       ( aTaskName        )
                    .agentBaseUrl   ( agent.getUrl()            )
                    .taskBytes      ( readAllBytes(aTaskFile.toPath())   )
                    .taskType       ( TaskType.DOCKER_CHECK     )
                    .accessToken    ( agent.getToken()          )
                    .build());
        } catch (Exception e) {
            LOG.error("Cannot check task " + aTaskName, e);
            return SendTaskResult.builder()
                    .statusCode(-500)
                    .text(e.getMessage())
                    .build();
        }
    }
}
