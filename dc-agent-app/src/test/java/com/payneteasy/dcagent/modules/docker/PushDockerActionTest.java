package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.modules.docker.dirs.TempDir;
import org.junit.Test;

import java.io.File;

public class PushDockerActionTest {

    @Test
    public void push_service() {
        TempDir               tempDir               = new TempDir(new File("./target/temp-dir")).createDir();
        ServicesDefinitionDir servicesDefinitionDir = new ServicesDefinitionDir(new File("./target/service.d"));
        ServicesLogDir        servicesLogDir        = new ServicesLogDir(new File("./target/log"));

        PushDockerAction pushDockerAction = new PushDockerAction("java-app", tempDir, servicesDefinitionDir, servicesLogDir);
        
        pushDockerAction.pushService(new File("./src/test/resources/dc-agent-docker-0.0.1.zip"));
    }
}