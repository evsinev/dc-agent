package com.payneteasy.dcagent.core.config.model.docker;

import com.payneteasy.dcagent.core.modules.docker.ActionLoggerImpl;
import com.payneteasy.dcagent.core.modules.docker.PushDockerAction;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.util.DeleteDirRecursively;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PushDockerActionTest {

    private static final File ZIP = new File("./src/test/resources/dc-agent-docker-0.0.1.zip");

    @Test
    public void push_service() {
        TempDir               tempDir               = new TempDir(new File("./target/temp-dir"), true).createDir();
        ServicesDefinitionDir servicesDefinitionDir = new ServicesDefinitionDir(new File("./target/service.d"));
        ServicesLogDir        servicesLogDir        = new ServicesLogDir(new File("./target/log"));

        PushDockerAction pushDockerAction = new PushDockerAction("java-app", tempDir, servicesDefinitionDir, servicesLogDir, new ActionLoggerImpl(), FileSystemWriterImpl::new);
        pushDockerAction.pushService(ZIP);

        PushDockerAction checkDockerAction = new PushDockerAction("java-app", tempDir, servicesDefinitionDir, servicesLogDir, new ActionLoggerImpl(), FileSystemCheckImpl::new);
        checkDockerAction.pushService(ZIP);
    }

    @Test
    public void deletes_temp_dir_when_enabled() {
        File root = freshRoot("./target/temp-delete");
        push(root, true);
        assertEquals("extracted dir should be deleted", 0, dockerDirCount(root));
    }

    @Test
    public void keeps_temp_dir_when_disabled() {
        File root = freshRoot("./target/temp-keep");
        push(root, false);
        assertTrue("extracted dir should be kept", dockerDirCount(root) > 0);
    }

    private static void push(File aRoot, boolean aDeleteAfterExtract) {
        TempDir               tempDir               = new TempDir(aRoot, aDeleteAfterExtract).createDir();
        ServicesDefinitionDir servicesDefinitionDir = new ServicesDefinitionDir(new File("./target/service.d"));
        ServicesLogDir        servicesLogDir        = new ServicesLogDir(new File("./target/log"));

        new PushDockerAction("java-app", tempDir, servicesDefinitionDir, servicesLogDir, new ActionLoggerImpl(), FileSystemWriterImpl::new)
                .pushService(ZIP);
    }

    private static int dockerDirCount(File aRoot) {
        File[] dirs = aRoot.listFiles((dir, name) -> name.startsWith("docker-java-app-"));
        return dirs == null ? 0 : dirs.length;
    }

    private static File freshRoot(String aPath) {
        File root = new File(aPath);
        new DeleteDirRecursively(root.getParentFile()).deleteDirIfExists(root);
        root.mkdirs();
        return root;
    }
}
