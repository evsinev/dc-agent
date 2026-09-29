package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A typo in dc-docker.yml fails DOCKER_CHECK and DOCKER_PUSH before anything is written. */
public class PushDockerActionStrictKeysTest {

    private static final String NAME = "strict-keys";

    private Path base;
    private Path app;

    @Before
    public void createDirs() throws IOException {
        base = Files.createTempDirectory("push-strict").toRealPath();
        app  = Files.createDirectories(base.resolve("app"));
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void a_typo_fails_check_and_push_before_any_change() throws IOException {
        File zip = zip("readOnly");

        for (IFileSystemFactory factory : new IFileSystemFactory[]{FileSystemCheckImpl::new, FileSystemWriterImpl::new}) {
            assertThatThrownBy(() -> push(zip, factory))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dc-docker.yml: unknown or duplicate keys")
                    .hasMessageContaining("volumes[0].directoryOrCreate.readOnly: unknown key (did you mean 'readonly'?)");
        }

        assertThat(base.resolve("service")).doesNotExist();
        assertThat(base.resolve("data")).doesNotExist();
    }

    @Test
    public void the_same_config_without_the_typo_passes_check_and_push() throws IOException {
        File zip = zip("readonly");

        push(zip, FileSystemCheckImpl::new);
        assertThat(base.resolve("service")).doesNotExist();
        assertThat(base.resolve("data")).doesNotExist();

        push(zip, FileSystemWriterImpl::new);

        assertThat(base.resolve("service/" + NAME + "/run")).exists();
        assertThat(base.resolve("data")).isDirectory();
    }

    private void push(File aZip, IFileSystemFactory aFactory) {
        TempDir               tempDir     = new TempDir(base.resolve("tmp").toFile(), true);
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(base.resolve("service").toFile());
        ServicesLogDir        logDir      = new ServicesLogDir(base.resolve("log").toFile());

        new PushDockerAction(NAME, tempDir, servicesDir, logDir, (aPattern, args) -> { }, aFactory).pushService(aZip);
    }

    private File zip(String aReadonlyKey) throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: " + NAME + "\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "directories:\n"
                + "  sourceBaseDir: " + app + "\n"
                + "  destinationBaseDir: /opt/app\n"
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      source: " + base.resolve("data") + "\n"
                + "      destination: /data\n"
                + "      " + aReadonlyKey + ": true\n";

        File zip = base.resolve("task.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dc-docker.yml"));
            out.write(yaml.getBytes(UTF_8));
            out.closeEntry();
        }
        return zip;
    }
}
