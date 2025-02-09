package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.DockerImage;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityCapabilities;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;


public class DockerRunFileBuilderTest {

    private static final Logger LOG = LoggerFactory.getLogger( DockerRunFileBuilderTest.class );

    @Test
    public void test() {
        DockerRunFileBuilder builder = new DockerRunFileBuilder();
        String text = builder.createRunFileTextInternal(TDocker.builder()
                .image(DockerImage.builder()
                        .name("image-1")
                        .build()
                )
                .securityContext(TSecurityContext.builder()
                        .capabilities(TSecurityCapabilities.builder()
                                .add(Arrays.asList("IPC_LOCK"))
                                .build()
                        )
                        .build()
                )
                .build()
                , "/tmp/env-dir"
        );

        LOG.debug("Text is {}", text);
    }
}