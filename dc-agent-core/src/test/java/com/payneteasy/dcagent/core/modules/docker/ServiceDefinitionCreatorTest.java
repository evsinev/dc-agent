package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import com.payneteasy.dcagent.core.modules.docker.resolver.RecordingFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/** The service directory gets 0755 when the agent creates it, whatever the umask. */
public class ServiceDefinitionCreatorTest {

    private Path base;

    @Before
    public void createBase() throws IOException {
        base = Files.createTempDirectory("service-def").toRealPath();
    }

    @After
    public void deleteBase() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void a_new_service_dir_gets_an_explicit_mode_and_an_existing_one_is_kept() throws IOException {
        ServicesDefinitionDir dirs = new ServicesDefinitionDir(base.resolve("service").toFile());
        Path serviceDir = base.resolve("service/app");

        // a directory 0777 already there (made by hand): kept, not silently fixed
        Path other = Files.createDirectories(base.resolve("service/other"));
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwxrwxrwx"));

        new ServiceDefinitionCreator(dirs, new FileSystemWriterImpl((aPattern, args) -> { })).createService("app", "#!/bin/sh\n", "#!/bin/sh\n", null);
        new ServiceDefinitionCreator(dirs, new FileSystemWriterImpl((aPattern, args) -> { })).createService("other", "#!/bin/sh\n", "#!/bin/sh\n", null);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(serviceDir))).isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(other))).isEqualTo("rwxrwxrwx");
    }

    @Test
    public void asks_the_file_system_for_mode_0755_on_the_service_dir() {
        ServicesDefinitionDir dirs = new ServicesDefinitionDir(base.resolve("service").toFile());
        RecordingFileSystem   fs   = new RecordingFileSystem();

        new ServiceDefinitionCreator(dirs, fs).createService("app", "#!/bin/sh\n", "#!/bin/sh\n", null);

        assertThat(fs.calls).contains("createDirectories " + base.resolve("service/app") + " 0755");
    }
}
