package com.payneteasy.dcagent.core.job.impl;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.job.messages.CreateJobParam;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.SecureKeys;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;


public class CreateJobServiceImplTest {

    private static final Logger LOG = LoggerFactory.getLogger( CreateJobServiceImplTest.class );

    private final SecureKeys           secureKeys = new SecureKeys();
    private final CreateJobServiceImpl service    = new CreateJobServiceImpl();

    @Test
    public void create_job() throws IOException {

        PrivateKey      privateKey      = secureKeys.loadPrivateKeyFile(new File("src/test/resources/dc-agent-config/client.key"));
        File            certificateFile = new File("src/test/resources/dc-agent-config/client.crt");
        X509Certificate certificate     = secureKeys.loadCertificate(certificateFile);

        try(TempFile tempFile = service.createJob(CreateJobParam.builder()
                        .jobId             ( UUID.randomUUID().toString() )
                        .taskName          ( "docker-test" )
                        .taskType          ( TaskType.DOCKER)
                        .taskFile          ( new File("src/test/resources/dc-agent-docker-0.0.1.zip"))
                        .privateKey        ( privateKey )
                        .certificate       ( certificate)
                        .certificateFile   ( certificateFile)
                        .consumerKey       ( "dc-agent-test-ca")
                .build())) {

            LOG.debug("temp file is {}", tempFile.getFile().getAbsolutePath());

            assertThat(tempFile).isNotNull();
        }



    }
}