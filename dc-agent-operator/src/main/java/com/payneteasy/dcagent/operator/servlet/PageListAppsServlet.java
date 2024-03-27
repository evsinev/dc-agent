package com.payneteasy.dcagent.operator.servlet;

import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.freemarker.FreemarkerTemplate;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

public class PageListAppsServlet extends SafeHttpServlet {

    private final IAppService        appService;
    private final FreemarkerTemplate template;

    public PageListAppsServlet(FreemarkerFactory aFreemarkerFactory, IAppService aAppViewService) {
        template   = aFreemarkerFactory.template("page-app-list.html");
        appService = aAppViewService;
    }

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {
        aResponse.setContentType("text/html");
        template.instance()
                .add("apps", appService.listApps())
                .write(aResponse);
    }
}
