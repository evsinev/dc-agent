package com.payneteasy.dcagent.controller.servlet;

import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

public class ManageViewJobServlet extends SafeHttpServlet {

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {
        PathParameters pathParameters = new PathParameters(aRequest.getRequestUrl());
        String         jobId          = pathParameters.getLast();
        aResponse.setContentType("text/html");
        aResponse.write(" job id is " + jobId);
    }
}
