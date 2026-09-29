package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The directory a task is extracted into: fresh, private and in a temp root nobody else can change
 * — a directory prepared under a predictable name must never be reused.
 */
public class PushDockerActionWorkDirTest {

    private static final String NAME = "work-dir";

    private Path base;
    private Path app;

    @Before
    public void createDirs() throws IOException {
        base = Files.createTempDirectory("push-work-dir").toRealPath();
        app  = Files.createDirectories(base.resolve("app"));
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void extracts_into_a_private_directory() throws IOException {
        Path tmp = Files.createDirectory(base.resolve("tmp"));

        push(tmp, false, randomSuffix());

        Path workDir = single(workDirs(tmp));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(workDir))).isEqualTo("rwx------");
        assertThat(workDir.resolve("dc-docker.yml")).exists();
    }

    @Test
    public void does_not_reuse_a_directory_that_already_has_the_name() throws IOException {
        Path tmp      = Files.createDirectory(base.resolve("tmp"));
        Path prepared = Files.createDirectory(tmp.resolve("docker-" + NAME + "-x"));
        Files.writeString(prepared.resolve("marker"), "someone else's");

        push(tmp, false, suffixes("x", "y"));

        assertThat(prepared.resolve("marker")).hasContent("someone else's");
        assertThat(prepared.resolve("dc-docker.yml")).doesNotExist();
        assertThat(tmp.resolve("docker-" + NAME + "-y/dc-docker.yml")).exists();
    }

    @Test
    public void creates_a_missing_temp_root_on_first_start() throws IOException {
        Path tmp = base.resolve("var/lib/dc-agent/tmp");

        push(tmp, false, randomSuffix());

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp))).isEqualTo("rwx------");
        assertThat(workDirs(tmp)).hasSize(1);
    }

    @Test
    public void refuses_a_temp_root_writable_by_others_before_extracting() throws IOException {
        Path tmp = Files.createDirectory(base.resolve("tmp"));
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> push(tmp, false, randomSuffix()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEMP_DIR");

        assertThat(workDirs(tmp)).isEmpty();
    }

    @Test
    public void keeps_only_a_plain_name_from_the_url_in_the_path() {
        assertThat(PushDockerAction.safeName("render-1.2_x")).isEqualTo("render-1.2_x");
        assertThat(PushDockerAction.safeName("../../etc")).isEqualTo("______etc");
        assertThat(PushDockerAction.safeName("a/b\\c d")).isEqualTo("a_b_c_d");
    }

    @Test
    public void deletes_the_work_dir_after_the_push() throws IOException {
        Path tmp = Files.createDirectory(base.resolve("tmp"));

        push(tmp, true, randomSuffix());

        assertThat(workDirs(tmp)).isEmpty();
    }

    private void push(Path aTempDir, boolean aDelete, Supplier<String> aSuffix) throws IOException {
        TempDir               tempDir     = new TempDir(aTempDir.toFile(), aDelete);
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(base.resolve("service").toFile());
        ServicesLogDir        logDir      = new ServicesLogDir(base.resolve("log").toFile());

        new PushDockerAction(NAME, tempDir, servicesDir, logDir, (aPattern, args) -> { }, FileSystemWriterImpl::new, aSuffix)
                .pushService(zip());
    }

    private File zip() throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: " + NAME + "\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "directories:\n"
                + "  sourceBaseDir: " + app + "\n"
                + "  destinationBaseDir: /opt/app\n"
                + "volumes: []\n";

        File zip = base.resolve("task.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dc-docker.yml"));
            out.write(yaml.getBytes(UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static List<Path> workDirs(Path aTempDir) throws IOException {
        try (Stream<Path> children = Files.list(aTempDir)) {
            return children.filter(path -> path.getFileName().toString().startsWith("docker-" + NAME + "-")).toList();
        }
    }

    private static Path single(List<Path> aPaths) {
        assertThat(aPaths).hasSize(1);
        return aPaths.get(0);
    }

    private static Supplier<String> randomSuffix() {
        return () -> Long.toHexString(System.nanoTime());
    }

    private static Supplier<String> suffixes(String... aSuffixes) {
        Iterator<String> iterator = List.of(aSuffixes).iterator();
        return iterator::next;
    }
}
