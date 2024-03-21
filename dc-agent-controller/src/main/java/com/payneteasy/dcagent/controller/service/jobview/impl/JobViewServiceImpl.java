package com.payneteasy.dcagent.controller.service.jobview.impl;

import com.payneteasy.dcagent.controller.service.config.IControllerConfigService;
import com.payneteasy.dcagent.controller.service.config.model.TAgentHost;
import com.payneteasy.dcagent.controller.service.jobview.IJobViewService;
import com.payneteasy.dcagent.controller.service.jobview.JobViewRequest;
import com.payneteasy.dcagent.controller.service.jobview.JobViewResult;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.job.create.model.TJobDefinition;
import com.payneteasy.dcagent.core.job.create.model.TJobSignatureParam;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.SendTaskParam;
import com.payneteasy.dcagent.core.task.send.SendTaskResult;
import com.payneteasy.dcagent.core.util.zip.ZipFileItem;
import com.payneteasy.dcagent.core.util.zip.ZipFileViewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Date;

import static com.payneteasy.dcagent.core.util.Gsons.PRETTY_GSON;
import static com.payneteasy.dcagent.core.util.SafeFiles.ensureFileExists;

public class JobViewServiceImpl implements IJobViewService {

    private static final Logger LOG = LoggerFactory.getLogger( JobViewServiceImpl.class );

    private final ISendTaskService         sendTaskService;
    private final IControllerConfigService configService;
    private final File                     jobsDir;

    public JobViewServiceImpl(ISendTaskService sendTaskService, IControllerConfigService configService, File jobsDir) {
        this.sendTaskService = sendTaskService;
        this.configService   = configService;
        this.jobsDir         = jobsDir;
    }

    @Override
    public JobViewResult viewJob(JobViewRequest aRequest) {
        File               jobFile         = ensureFileExists(new File(jobsDir, aRequest.getJobId() + ".zip"));
        ZipFileViewer      zipFileViewer   = new ZipFileViewer(jobFile);
        TJobDefinition     job             = PRETTY_GSON.fromJson(zipFileViewer.getItemText("job.json"), TJobDefinition.class);
        TJobSignatureParam signatureParam  = job.getSignatureParam();
        SendTaskResult     checkTaskResult = getCheckTaskText(job, zipFileViewer.getItem("task.zip").orElseThrow(() -> new IllegalArgumentException("No task.zip")));

        return JobViewResult.builder()
                .jobId(aRequest.getJobId())
                .consumerKey(signatureParam.getConsumerKey())
                .jobCreatedDateFormatted(new Date(signatureParam.getTimestampMs()).toString())
                .taskCheckText(checkTaskResult.getText())
                .taskCheckColor(checkTaskResult.getStatusCode() == 200 ? "black" : "red-600")
                .taskName(job.getTaskName())
                .taskType(job.getTaskType())
                .taskHost(job.getTaskHost())
                .agentUrl(configService.findAgentHost(job.getTaskHost()).orElse(TAgentHost.builder().url("no agent url").build()).getUrl())
                .build();
    }

    private SendTaskResult getCheckTaskText(TJobDefinition aJob, ZipFileItem aTaskZipItem) {
        try {
            TAgentHost agent = configService.findAgentHost(aJob.getTaskHost()).orElseThrow(() -> new IllegalArgumentException("No config for host " + aJob.getTaskHost()));
            return sendTaskService.sendTask(SendTaskParam.builder()
                    .taskName       ( aJob.getTaskName()        )
                    .agentBaseUrl   ( agent.getUrl()            )
                    .taskBytes      ( aTaskZipItem.getBytes()   )
                    .taskType       ( TaskType.DOCKER_CHECK     )
                    .accessToken    ( agent.getToken()          )
                    .build());
        } catch (Exception e) {
            LOG.error("Cannot invoke check for job " + aJob.getJobId(), e);
            return SendTaskResult.builder()
                    .statusCode(-500)
                    .text(e.getMessage())
                    .build();
        }
    }
}
