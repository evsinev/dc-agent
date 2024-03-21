package com.payneteasy.dcagent.controller.servlet;

import com.payneteasy.dcagent.controller.service.jobview.IJobViewService;
import com.payneteasy.dcagent.controller.service.jobview.JobViewRequest;
import com.payneteasy.dcagent.controller.service.jobview.JobViewResult;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.freemarker.FreemarkerTemplate;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

public class ManageViewJobServlet extends SafeHttpServlet {

    private final IJobViewService    jobViewService;
    private final FreemarkerTemplate template;

    public ManageViewJobServlet(FreemarkerFactory aFreemarkerFactory, IJobViewService aJobViewService) {
        template = aFreemarkerFactory.template("page-job-view.html");
        jobViewService = aJobViewService;
    }

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {
        PathParameters pathParameters = new PathParameters(aRequest.getRequestUrl());
        String         jobId          = pathParameters.getLast();
        JobViewResult  job            = jobViewService.viewJob(JobViewRequest.builder().jobId(jobId).build());

        aResponse.setContentType("text/html");
        template.instance()
                .add("jobId"           , jobId              )
                .add("taskName"        , job.getTaskName()  )
                .add("taskHost"        , job.getTaskHost()  )
                .add("taskType"        , job.getTaskType()  )
                .add("jobDateFormatted", job.getJobCreatedDateFormatted())
                .add("taskCheckText"   , job.getTaskCheckText())
                .add("taskCheckColor"  , job.getTaskCheckColor())
                .add("agentUrl"        , job.getAgentUrl())
                .add("consumerKey"     , job.getConsumerKey())
                .write(aResponse);
    }
}
