package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.config.model.docker.*;
import com.payneteasy.dcagent.config.model.docker.volumes.DirConfigVolume;
import org.junit.Test;

import java.util.Arrays;

public class DockerRunFileBuilderTest {

    @Test
    public void buildText() {
        String text = DockerRunFileBuilder.createRunFileText(TDocker.builder()
                .name("dc-agent")
                .directories(DockerDirectories.builder()
                        .destinationBaseDir("/opt/dc-agent")
                        .sourceBaseDir("/opt/dc-agent")
                        .containerWorkingDir("/opt/dc-agent")
                        .build())
                .env(Arrays.asList(EnvVariable.builder()
                                .name("NAME_1")
                                .value("value-1")
                                .build()
                        , EnvVariable.builder()
                                .name("NAME_2")
                                .value("value-2")
                                .build()
                ))
                .boundVariables(Arrays.asList(
                        BoundVariable.builder()
                                .name("VAR_1")
                                .value("val-1")
                                .build(),
                        BoundVariable.builder()
                                .name("VAR_2")
                                .value("val-2")
                                .build()
                ))
                .image(DockerImage.builder()
                        .name("amazoncorretto:8-alpine3.16-jre")
                        .build())
                .args(new String[]{"java", "-jar", "/opt/dc-agent/versions/dc-agent.jar.1.0-3"})
                .volumes(Arrays.asList(
                        DockerVolume.builder()
                                .dirConfig(DirConfigVolume.builder()
                                        .source("/opt/dc-agent/config")
                                        .destination("/opt/dc-agent/config")
                                        .readonly(true)
                                        .build())
                                .build()
                ))
                .build());
        System.out.println("text = " + text);
    }
}