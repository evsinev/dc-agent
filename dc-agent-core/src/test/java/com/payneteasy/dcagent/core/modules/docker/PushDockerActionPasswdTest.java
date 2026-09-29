package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import com.payneteasy.dcagent.core.modules.docker.runtime.ContainerRuntime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.helpers.MessageFormatter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** securityContext.passwdEntry end to end: the runtime check, container-passwd for docker, podman's own option. */
public class PushDockerActionPasswdTest {

    private static final String NAME     = "passwd-e2e";
    private static final String TEMPLATE = "probe-$UID:*:$UID:$GID::/tmp:/sbin/nologin";
    private static final String PASSWD   = "  passwdEntry: \"" + TEMPLATE + "\"\n";

    private final List<String> messages = new ArrayList<>();

    private Path base;
    private Path app;

    @Before
    public void createDirs() throws IOException {
        base = Files.createTempDirectory("push-passwd").toRealPath();
        app  = Files.createDirectories(base.resolve("app"));
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void docker_check_reports_the_file_and_push_writes_it() throws IOException {
        File zip = zip(PASSWD, "");

        push(zip, FileSystemCheckImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(base.resolve("service")).doesNotExist();
        assertThat(messages).anyMatch(message -> message.contains("Will write file " + passwdFile()));

        push(zip, FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(passwdFile()).hasContent("root:x:0:0:root:/root:/sbin/nologin\nprobe-20500:*:20500:20500::/tmp:/sbin/nologin");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(passwdFile()))).isEqualTo("rw-r--r--");
        assertThat(runFile()).contains("-v " + passwdFile() + ":/etc/passwd:ro").doesNotContain("--passwd-entry");

        messages.clear();
        push(zip, FileSystemCheckImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(messages).noneMatch(message -> message.contains("container-passwd"));
    }

    @Test
    public void check_reports_a_wrong_mode_of_the_same_content() throws IOException {
        File zip = zip(PASSWD, "");
        push(zip, FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        Files.setPosixFilePermissions(passwdFile(), PosixFilePermissions.fromString("rw-------"));

        push(zip, FileSystemCheckImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(messages).anyMatch(message -> message.contains("Will set mode 0644 on " + passwdFile()));

        push(zip, FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(passwdFile()))).isEqualTo("rw-r--r--");
    }

    @Test
    public void removing_passwd_entry_or_moving_to_podman_deletes_the_file() throws IOException {
        push(zip(PASSWD, ""), FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(passwdFile()).exists();

        push(zip("", ""), FileSystemCheckImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(messages).anyMatch(message -> message.contains("Will delete " + passwdFile()));
        assertThat(passwdFile()).exists();

        push(zip("", ""), FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        assertThat(passwdFile()).doesNotExist();

        push(zip(PASSWD, ""), FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        push(zip(PASSWD, ""), FileSystemWriterImpl::new, ContainerRuntime.PODMAN, "podman version 5.8.0");
        assertThat(passwdFile()).doesNotExist();
        assertThat(runFile()).contains("  --passwd-entry '" + TEMPLATE + "' \\");
    }

    @Test
    public void podman_writes_no_file() throws IOException {
        push(zip(PASSWD, ""), FileSystemWriterImpl::new, ContainerRuntime.PODMAN, "podman version 5.8.0");

        assertThat(passwdFile()).doesNotExist();
        assertThat(runFile()).contains("--passwd-entry '" + TEMPLATE + "'");
    }

    @Test
    public void a_runtime_that_is_not_what_docker_is_fails_before_any_change() throws IOException {
        File zip = zip(PASSWD, "");

        assertThatThrownBy(() -> push(zip, FileSystemWriterImpl::new, ContainerRuntime.PODMAN, "Docker version 29.4.0"))
                .hasMessageContaining("CONTAINER_RUNTIME is podman but 'docker --version' says 'Docker version 29.4.0'; set CONTAINER_RUNTIME=docker");
        assertThatThrownBy(() -> push(zip, FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "podman version 5.8.0"))
                .hasMessageContaining("set CONTAINER_RUNTIME=podman");

        assertThat(base.resolve("service")).doesNotExist();
    }

    @Test
    public void a_config_without_passwd_entry_does_not_ask_the_runtime() throws IOException {
        push(zip("", ""), FileSystemWriterImpl::new, ContainerRuntime.PODMAN, null);

        assertThat(runFile()).doesNotContain("--passwd-entry").doesNotContain("/etc/passwd");
    }

    @Test
    public void mounts_that_would_hide_etc_passwd_are_refused() {
        String etcVolume = "  - linkToHostDirectory:\n      source: /opt/etc\n      destination: /etc/\n";

        assertThatThrownBy(() -> push(zip(PASSWD, etcVolume), FileSystemCheckImpl::new, ContainerRuntime.PODMAN, "podman version 5.8.0"))
                .hasMessageContaining("a volume on /etc would hide or replace the /etc/passwd of securityContext.passwdEntry");
        assertThatThrownBy(() -> push(zip(PASSWD + "  tmpfs: [ \"/etc\" ]\n", ""), FileSystemCheckImpl::new, ContainerRuntime.PODMAN, "podman version 5.8.0"))
                .hasMessageContaining("securityContext.tmpfs[0]: /etc would hide the /etc/passwd");
        assertThat(base.resolve("service")).doesNotExist();
    }

    @Test
    public void a_relative_destination_resolving_to_etc_passwd_is_refused() {
        String relative = "  - linkToHostFile:\n      source: /opt/custom-passwd\n      destination: passwd\n";

        assertThatThrownBy(() -> push(zip(PASSWD, relative, true, "/etc"), FileSystemCheckImpl::new, ContainerRuntime.PODMAN, "podman version 5.8.0"))
                .hasMessageContaining("a volume on /etc/passwd would hide or replace");
    }

    @Test
    public void a_link_at_the_file_place_fails_check_and_push_alike_before_run_changes() throws IOException {
        push(zip(PASSWD, ""), FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0");
        String run = runFile();
        Files.delete(passwdFile());
        Files.createSymbolicLink(passwdFile(), base.resolve("elsewhere"));

        // a changed config: the run file would differ, so an early refusal is visible
        File changed = zip(PASSWD + "  tmpfs: [ \"/tmp\" ]\n", "");
        for (IFileSystemFactory factory : new IFileSystemFactory[]{FileSystemCheckImpl::new, FileSystemWriterImpl::new}) {
            assertThatThrownBy(() -> push(changed, factory, ContainerRuntime.DOCKER, "Docker version 29.4.0"))
                    .hasMessageContaining(passwdFile() + " is a symbolic link");
        }
        assertThat(base.resolve("elsewhere")).doesNotExist();
        assertThat(runFile()).isEqualTo(run);
    }

    @Test
    public void passwd_entry_needs_run_as_user_and_group() {
        assertThatThrownBy(() -> push(zip(PASSWD, "", false), FileSystemCheckImpl::new, ContainerRuntime.PODMAN, "podman version 5.8.0"))
                .hasMessageContaining("securityContext.passwdEntry needs runAsUser and runAsGroup");
    }

    @Test
    public void an_untrusted_service_dir_is_refused_before_anything_changes() throws IOException {
        Path serviceDir = Files.createDirectories(base.resolve("service/" + NAME));
        Files.writeString(serviceDir.resolve("run"), "old run");
        Files.setPosixFilePermissions(serviceDir, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> push(zip(PASSWD, ""), FileSystemWriterImpl::new, ContainerRuntime.DOCKER, "Docker version 29.4.0"))
                .hasMessageContaining("directory " + serviceDir);
        assertThat(passwdFile()).doesNotExist();
        assertThat(serviceDir.resolve("run")).hasContent("old run");
    }

    private Path passwdFile() {
        return base.resolve("service/" + NAME + "/container-passwd");
    }

    private String runFile() throws IOException {
        return Files.readString(base.resolve("service/" + NAME + "/run"));
    }

    private void push(File aZip, IFileSystemFactory aFactory, ContainerRuntime aRuntime, String aVersion) {
        TempDir               tempDir     = new TempDir(base.resolve("tmp").toFile(), true);
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(base.resolve("service").toFile());
        ServicesLogDir        logDir      = new ServicesLogDir(base.resolve("log").toFile());
        IActionLogger         logger      = (aPattern, args) -> messages.add(MessageFormatter.arrayFormat(aPattern, args).getMessage());

        new PushDockerAction(NAME, tempDir, servicesDir, logDir, logger, aFactory, () -> "s" + System.nanoTime(), aRuntime, () -> {
            if (aVersion == null) {
                throw new AssertionError("the runtime must not be asked for a config without passwdEntry");
            }
            return aVersion;
        }).pushService(aZip);
    }

    private File zip(String aSecurityExtra, String aVolumes) throws IOException {
        return zip(aSecurityExtra, aVolumes, true);
    }

    private File zip(String aSecurityExtra, String aVolumes, boolean aGroup) throws IOException {
        return zip(aSecurityExtra, aVolumes, aGroup, "/opt/app");
    }

    private File zip(String aSecurityExtra, String aVolumes, boolean aGroup, String aDestinationBaseDir) throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: " + NAME + "\n"
                + "image:\n"
                + "  name: \"alma9-java-21-temurin-jre:1\"\n"
                + "directories:\n"
                + "  sourceBaseDir: " + app + "\n"
                + "  destinationBaseDir: " + aDestinationBaseDir + "\n"
                + "securityContext:\n"
                + "  runAsUser: 20500\n"
                + (aGroup ? "  runAsGroup: 20500\n" : "")
                + "  readOnlyRootFilesystem: true\n"
                + aSecurityExtra
                + "volumes:\n"
                + (aVolumes.isEmpty() ? "  - linkToHostFile:\n      source: /etc/localtime\n      destination: /etc/localtime\n      readonly: true\n" : aVolumes);

        File zip = base.resolve("task.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dc-docker.yml"));
            out.write(yaml.getBytes(UTF_8));
            out.closeEntry();
        }
        return zip;
    }
}
