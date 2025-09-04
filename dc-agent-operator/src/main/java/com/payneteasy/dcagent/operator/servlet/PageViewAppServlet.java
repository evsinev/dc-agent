package com.payneteasy.dcagent.operator.servlet;

import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.operator.service.appview.IAppViewService;
import com.payneteasy.dcagent.operator.service.appview.messages.AppViewRequest;
import com.payneteasy.dcagent.operator.service.appview.model.AppViewResult;
import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.freemarker.FreemarkerTemplate;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

public class PageViewAppServlet extends SafeHttpServlet {

    private final IAppViewService    appViewService;
    private final FreemarkerTemplate template;

    public PageViewAppServlet(FreemarkerFactory aFreemarkerFactory, IAppViewService aAppViewService) {
        template = aFreemarkerFactory.template("page-app-view.html");
        appViewService = aAppViewService;
    }

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {
        PathParameters pathParameters = new PathParameters(aRequest.getRequestUrl());
        String        jobId   = pathParameters.getLast();
        AppViewResult app = appViewService.viewApp(new AppViewRequest(jobId));

        aResponse.setContentType("text/html");
        template.instance()
                .add("appName"         , jobId              )
                .add("taskName"        , app.getTaskName()  )
                .add("taskHost"        , app.getTaskHost()  )
                .add("taskType"        , app.getTaskType()  )
                .add("taskCheckText"   , app.getTaskCheckText())
                .add("taskCheckColor"  , app.getTaskCheckColor())
                .add("agentUrl"        , app.getAgentUrl())
                .write(aResponse);
    }
}
