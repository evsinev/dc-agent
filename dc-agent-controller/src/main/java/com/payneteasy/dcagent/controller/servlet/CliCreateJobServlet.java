package com.payneteasy.dcagent.controller.servlet;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.job.JobFile;
import com.payneteasy.dcagent.core.job.check.CheckJobSignatureParam;
import com.payneteasy.dcagent.core.job.check.ICheckJobSignatureService;
import com.payneteasy.dcagent.core.job.check.impl.CheckJobSignatureServiceImpl;
import com.payneteasy.dcagent.core.job.send.SendJobResult;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.core.util.SecureKeys;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

import java.io.File;
import java.io.InputStream;
import java.security.cert.X509Certificate;

import static com.payneteasy.dcagent.core.util.Streams.writeFile;

public class CliCreateJobServlet extends SafeHttpServlet {

    private final File   jobsDir;
    private final Gson   gson;
    private final String jobUrlPrefix;
    private final File   certDir;

    private final SecureKeys                secureKeys        = new SecureKeys();
    private final ICheckJobSignatureService checkJobSignature = new CheckJobSignatureServiceImpl();

    public CliCreateJobServlet(File jobsDir, Gson gson, String jobUrlPrefix, File aCertDir) {
        this.jobsDir      = jobsDir;
        this.gson         = gson;
        this.jobUrlPrefix = jobUrlPrefix;
        this.certDir      = aCertDir;
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

        JobFile         job         = new JobFile(jobFile);
        X509Certificate certificate = secureKeys.loadCertificate(new File(certDir, job.getJobDefinition().getSignatureParam().getConsumerKey() + ".crt"));

        checkJobSignature.checkJobSignature(CheckJobSignatureParam.builder()
                        .consumerCertificate        ( certificate                )
                        .jobJsonFileBytes           ( job.getJobBytes()          )
                        .jobSignatureJsonFileBytes  ( job.getJobSignatureBytes() )
                        .taskZipFileBytes           ( job.getTaskBytes()         )
                .build());

        SendJobResult jobResponse = SendJobResult.builder()
                .jobUrl(jobUrlPrefix + jobId)
                .build();

        aResponse.setContentType("application/json");
        aResponse.write(gson.toJson(jobResponse));

    }
}
