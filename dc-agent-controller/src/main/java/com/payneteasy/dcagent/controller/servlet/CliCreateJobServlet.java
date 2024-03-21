package com.payneteasy.dcagent.controller.servlet;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.job.send.SendJobResult;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

import java.io.File;
import java.io.InputStream;

import static com.payneteasy.dcagent.core.util.Streams.writeFile;

public class CliCreateJobServlet extends SafeHttpServlet {

    private final File   jobsDir;
    private final Gson   gson;
    private final String jobUrlPrefix;

    public CliCreateJobServlet(File jobsDir, Gson gson, String jobUrlPrefix) {
        this.jobsDir      = jobsDir;
        this.gson         = gson;
        this.jobUrlPrefix = jobUrlPrefix;
    }

    @Override
    protected void doSafePost(SafeServletRequest aRequest, SafeServletResponse aResponse) {
        PathParameters pathParameters = new PathParameters(aRequest.getRequestUrl());
        String         jobId          = pathParameters.getLast();
        File           jobFile        = new File(jobsDir, jobId + ".zip");

        try (InputStream in = aRequest.getDelegate().getInputStream()) {
            writeFile(jobFile, in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write job file", e);
        }

        SendJobResult jobResponse = SendJobResult.builder()
                .jobUrl(jobUrlPrefix + jobId)
                .build();
        aResponse.setContentType("application/json");
        aResponse.write(gson.toJson(jobResponse));

    }
}
