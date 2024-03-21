package com.payneteasy.dcagent.controller.service.jobview.impl;

import com.payneteasy.dcagent.controller.service.config.IControllerConfigService;
import com.payneteasy.dcagent.controller.service.jobview.IJobViewService;
import com.payneteasy.dcagent.controller.service.jobview.JobViewRequest;
import com.payneteasy.dcagent.controller.service.jobview.JobViewResult;
import com.payneteasy.dcagent.core.job.create.model.TJobDefinition;
import com.payneteasy.dcagent.core.job.create.model.TJobSignatureParam;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.util.zip.ZipFileViewer;

import java.io.File;
import java.util.Date;

import static com.payneteasy.dcagent.core.util.Gsons.PRETTY_GSON;
import static com.payneteasy.dcagent.core.util.SafeFiles.ensureFileExists;

public class JobViewServiceImpl implements IJobViewService {

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
        File               jobFile        = ensureFileExists(new File(jobsDir, aRequest.getJobId() + ".zip"));
        ZipFileViewer      zipFileViewer  = new ZipFileViewer(jobFile);
        TJobDefinition     job            = PRETTY_GSON.fromJson(zipFileViewer.getItemText("job.json"), TJobDefinition.class);
        TJobSignatureParam signatureParam = job.getSignatureParam();

        return JobViewResult.builder()
                .jobId(aRequest.getJobId())
                .consumerKey(signatureParam.getConsumerKey())
                .jobCreatedDateFormatted(new Date(signatureParam.getTimestampMs()).toString())
                .taskCheckText("...")
                .taskName(job.getTaskName())
                .taskType(job.getTaskType())
                .taskHost(job.getTaskHost())
                .build();
    }
}
