package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.modules.docker.dirs.TempDir;
import org.junit.Test;

import java.io.File;

public class PushDockerActionTest {

    @Test
    public void push_service() {
        TempDir          tempDir          = new TempDir(new File("./target/temp-dir"));
        tempDir.createDir();
        PushDockerAction pushDockerAction = new PushDockerAction("java-app", tempDir);
        pushDockerAction.pushService(new File("./src/test/resources/dc-agent-docker-0.0.1.zip"));
    }
}