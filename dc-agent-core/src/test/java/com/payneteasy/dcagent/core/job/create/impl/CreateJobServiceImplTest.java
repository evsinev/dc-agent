package com.payneteasy.dcagent.core.job.create.impl;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.job.check.CheckJobSignatureParam;
import com.payneteasy.dcagent.core.job.check.ICheckJobSignatureService;
import com.payneteasy.dcagent.core.job.check.impl.CheckJobSignatureServiceImpl;
import com.payneteasy.dcagent.core.job.create.messages.CreateJobParam;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.SecureKeys;
import com.payneteasy.dcagent.core.util.zip.ZipFileViewer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Collections.singletonList;


public class CreateJobServiceImplTest {

    private static final Logger LOG = LoggerFactory.getLogger( CreateJobServiceImplTest.class );

    private final SecureKeys                secureKeys     = new SecureKeys();
    private final CreateJobServiceImpl      service        = new CreateJobServiceImpl();
    private final ICheckJobSignatureService checkSignature = new CheckJobSignatureServiceImpl();

    @Test
    public void create_job() throws IOException {

        PrivateKey      privateKey      = secureKeys.loadPrivateKeyFile(new File("src/test/resources/dc-agent-config/client.key"));
        File            certificateFile = new File("src/test/resources/dc-agent-config/client.crt");
        X509Certificate certificate     = secureKeys.loadCertificate(certificateFile);

        File taskFile = new File("src/test/resources/dc-agent-docker-0.0.1.zip");

        try(TempFile tempFile = service.createJob(CreateJobParam.builder()
                        .jobId             ( UUID.randomUUID().toString() )
                        .taskName          ( "docker-test" )
                        .taskType          ( TaskType.DOCKER)
                        .taskFile          ( taskFile )
                        .privateKey        ( privateKey )
                        .certificate       ( certificate)
                        .certificateFile   ( certificateFile)
                        .consumerKey       ( "dc-agent-test-ca")
                        .taskHost          ( "test-host-1" )
                .build())) {

            LOG.debug("temp file is {}", tempFile.getFile().getAbsolutePath());

            assertThat(tempFile).isNotNull();

            ZipFileViewer zipFileViewer = new ZipFileViewer(tempFile.getFile());

            checkSignature.checkJobSignature(CheckJobSignatureParam.builder()
                            .jobSignatureJsonFileBytes  ( zipFileViewer.getItemBytes("job-signature.json"))
                            .jobJsonFileBytes           ( zipFileViewer.getItemBytes("job.json"))
                            .consumerCertificate        ( certificate )
                            .taskZipFileBytes           ( Files.readAllBytes(taskFile.toPath()))
                    .build());
        }



    }
}