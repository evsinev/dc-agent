package com.payneteasy.dcagent.operator.service.appview.impl;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.exception.DcProblem;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.SendTaskParam;
import com.payneteasy.dcagent.core.task.send.SendTaskResult;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.model.TApp;
import com.payneteasy.dcagent.operator.service.appview.IAppViewService;
import com.payneteasy.dcagent.operator.service.appview.messages.AppPushRequest;
import com.payneteasy.dcagent.operator.service.appview.messages.AppStatusResponse;
import com.payneteasy.dcagent.operator.service.appview.messages.AppViewRequest;
import com.payneteasy.dcagent.operator.service.appview.model.AppStatusType;
import com.payneteasy.dcagent.operator.service.appview.model.AppViewResult;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.taskcreate.ITaskCreateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;
import static com.payneteasy.jetty.util.Strings.hasText;
import static com.payneteasy.jetty.util.Strings.isEmpty;
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
        return performAction(aRequest.getAppName(), TaskType.DOCKER_CHECK);
    }

    @Override
    public AppViewResult pushApp(AppPushRequest aRequest) {
        return performAction(aRequest.getAppName(), TaskType.DOCKER_PUSH);
    }


    private AppViewResult performAction(String aAppName, TaskType aTaskType) {
        TApp app = appService.getApp(aAppName);

        try(TempFile taskFile = taskCreateService.createTaskZipFile(app.getTaskName())) {
            String         taskName        = app.getTaskName();
            SendTaskResult checkTaskResult = runTask(taskName, app.getTaskHost(), taskFile.getFile(), aTaskType);

            return AppViewResult.builder()
                    .appName        ( aAppName)
                    .taskCheckText  ( checkTaskResult.getText())
                    .taskCheckColor ( checkTaskResult.getStatusCode() == 200 ? "black" : "red-600")
                    .taskName       ( taskName)
                    .taskType       ( app.getTaskType())
                    .taskHost       ( app.getTaskHost())
                    .agentUrl       ( configService.findAgentHost(app.getTaskHost()).orElse(TAgentHost.builder().url("no agent url").build()).getUrl())
                    .build();
        } catch (IOException e) {
            throw problem(DcProblem.CANNOT_CREATE_TASK_ZIP_FILE)
                    .exception(e);
        }
    }

    private SendTaskResult runTask(String aTaskName, String aHost, File aTaskFile, TaskType aTaskType) {
        try {
            TAgentHost agent = configService.findAgentHost(aHost).orElseThrow(() -> new IllegalArgumentException("No config for host " + aHost));

            return sendTaskService.sendTask(SendTaskParam.builder()
                    .taskName       ( aTaskName                        )
                    .agentBaseUrl   ( agent.getUrl()                   )
                    .taskBytes      ( readAllBytes(aTaskFile.toPath()) )
                    .taskType       ( aTaskType                        )
                    .accessToken    ( agent.getToken()                 )
                    .build());
        } catch (Exception e) {
            LOG.error("Cannot check task {}", aTaskName, e);
            return SendTaskResult.builder()
                    .statusCode(-500)
                    .text(e.getMessage())
                    .build();
        }
    }

    @Override
    public AppStatusResponse getAppStatus(AppViewRequest aRequest) {
        try {
            AppViewResult result = performAction(aRequest.getAppName(), TaskType.DOCKER_CHECK);

            if (!"black".equals(result.getTaskCheckColor())) {
                return AppStatusResponse.builder()
                        .status(AppStatusType.ERROR)
                        .errorMessage(trimLeft(result.getTaskCheckText(), 30))
                        .build();
            }

            if (hasText(result.getTaskCheckText())) {
                return AppStatusResponse.builder()
                        .status(AppStatusType.DRIFT)
                        .errorMessage("Drift")
                        .build();
            }

            return AppStatusResponse.builder()
                    .status(AppStatusType.OK)
                    .build();

        } catch (Exception e) {
            LOG.error("Cannot get app status", e);
            return AppStatusResponse.builder()
                    .status(AppStatusType.ERROR)
                    .errorMessage(trimLeft(e.getMessage(), 30))
                    .build();
        }
    }

    private String trimLeft(String aText, int aMaxLength) {
        if (isEmpty(aText)) {
            return aText;
        }

        if (aText.length() > aMaxLength) {
            return aText.substring(0, aMaxLength);
        }

        return aText;
    }
}
