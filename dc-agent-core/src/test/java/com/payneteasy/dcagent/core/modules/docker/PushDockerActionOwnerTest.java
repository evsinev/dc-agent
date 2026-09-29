package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import com.sun.security.auth.module.UnixSystem;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.helpers.MessageFormatter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Owner/mode through the whole PushDockerAction: CHECK reports the concrete change, PUSH makes
 * it, a second CHECK is silent. A config without the new fields gets no new file system action.
 */
public class PushDockerActionOwnerTest {

    private static final int    ME   = (int) new UnixSystem().getUid();
    private static final String NAME = "owner-e2e";

    private final List<String>  messages = new ArrayList<>();
    private final IActionLogger logger   = (aPattern, args) -> messages.add(MessageFormatter.arrayFormat(aPattern, args).getMessage());
    private final List<String>  applied  = new ArrayList<>();

    private Path base;
    private Path app;

    @Before
    public void createDirs() throws IOException {
        base = Files.createTempDirectory("push-owner").toRealPath();
        app  = Files.createDirectories(base.resolve("app"));
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void check_push_check() throws IOException {
        int  group = otherOwnGroup((Integer) Files.getAttribute(app, "unix:gid", LinkOption.NOFOLLOW_LINKS));
        Path state = app.resolve("state");
        File zip   = zip(""
                + "securityContext:\n"
                + "  runAsUser: " + ME + "\n"
                + "  runAsGroup: " + group + "\n"
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n"
                + "      owner: runAs\n"
                + "      mode: \"0770\"\n");

        push(zip, FileSystemCheckImpl::new);
        assertThat(ownerMessages()).containsExactly("🔑  Will set uid " + ME + ", gid " + group + ", mode 0770 on " + state);
        assertThat(Files.exists(state)).isFalse();

        messages.clear();
        push(zip, FileSystemWriterImpl::new);
        assertThat(ownerMessages()).hasSize(1);
        assertThat(Files.getAttribute(state, "unix:uid", LinkOption.NOFOLLOW_LINKS)).isEqualTo(ME);
        assertThat(Files.getAttribute(state, "unix:gid", LinkOption.NOFOLLOW_LINKS)).isEqualTo(group);
        assertThat(Files.getPosixFilePermissions(state)).isEqualTo(PosixFilePermissions.fromString("rwxrwx---"));
        assertThat(runFile()).contains("  --user " + ME + ":" + group + " \\\n");

        messages.clear();
        push(zip, FileSystemCheckImpl::new);
        assertThat(ownerMessages()).isEmpty();
        assertThat(applied).hasSize(3);
    }

    @Test
    public void unsafe_config_is_rejected_before_any_change() throws IOException {
        File zip = zip(""
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n"
                + "      owner: { user: " + ME + " }\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state/sub\n"
                + "      owner: { user: " + ME + " }\n");

        for (IFileSystemFactory factory : new IFileSystemFactory[]{FileSystemCheckImpl::new, FileSystemWriterImpl::new}) {
            assertThatThrownBy(() -> push(zip, factory)).hasMessageContaining("goes through");
        }
        assertThat(Files.exists(app.resolve("state"))).isFalse();
        assertThat(Files.exists(base.resolve("service"))).isFalse();
        assertThat(applied).isEmpty();
    }

    /** Code review 4, p. 2: the task is extracted into, and deleted from, the temp directory. */
    @Test
    public void owned_directory_containing_the_temp_directory_is_rejected() throws IOException {
        Files.createDirectories(app.resolve("state"));
        File zip = zip(""
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n"
                + "      owner: { user: " + ME + " }\n");

        assertThatThrownBy(() -> push(zip, FileSystemWriterImpl::new, app.resolve("state/tmp")))
                .hasMessageContainingAll("extracted task", "inside or through");
        assertThat(applied).isEmpty();
    }

    /**
     * Code review 4-2, p. 2: the agent writes by the path as configured, so a '..' in its own
     * directories is rejected, not normalized away for the check only.
     */
    @Test
    public void dot_dot_in_the_agent_service_dir_is_rejected() throws IOException {
        File zip = zip(""
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n"
                + "      owner: { user: " + ME + " }\n");

        Path servicesDir = app.resolve("state/hop/../../service.d");
        assertThatThrownBy(() -> push(zip, FileSystemCheckImpl::new, base.resolve("tmp"), servicesDir))
                .hasMessageContainingAll("service", "'..' are not allowed");
    }

    /**
     * No owner/mode: no preflight (the world-writable directory on the way would fail it) and no
     * applyOwner call.
     */
    @Test
    public void config_without_owner_gets_no_new_actions() throws IOException {
        Files.setPosixFilePermissions(app, PosixFilePermissions.fromString("rwxrwxrwx"));
        File zip = zip(""
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n");

        push(zip, FileSystemCheckImpl::new);
        push(zip, FileSystemWriterImpl::new);

        assertThat(applied).isEmpty();
        assertThat(ownerMessages()).isEmpty();
        assertThat(runFile()).doesNotContain("--user");
    }

    private List<String> ownerMessages() {
        List<String> owner = new ArrayList<>();
        for (String message : messages) {
            if (message.startsWith("🔑")) {
                owner.add(message);
            }
        }
        return owner;
    }

    private String runFile() throws IOException {
        return new String(Files.readAllBytes(base.resolve("service/" + NAME + "/run")), UTF_8);
    }

    private void push(File aZip, IFileSystemFactory aFactory) {
        push(aZip, aFactory, base.resolve("tmp"));
    }

    private void push(File aZip, IFileSystemFactory aFactory, Path aTempDir) {
        push(aZip, aFactory, aTempDir, base.resolve("service"));
    }

    private void push(File aZip, IFileSystemFactory aFactory, Path aTempDir, Path aServicesDir) {
        TempDir               tempDir     = new TempDir(aTempDir.toFile(), true).createDir();
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(aServicesDir.toFile());
        ServicesLogDir        logDir      = new ServicesLogDir(base.resolve("log").toFile());

        new PushDockerAction(NAME, tempDir, servicesDir, logDir, logger, aLogger -> new CountingFileSystem(aFactory.createFileSystem(aLogger)))
                .pushService(aZip);
    }

    private File zip(String aExtraYaml) throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: " + NAME + "\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "directories:\n"
                + "  sourceBaseDir: " + app + "\n"
                + "  destinationBaseDir: /opt/app\n"
                + aExtraYaml;

        File zip = base.resolve("task.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dc-docker.yml"));
            out.write(yaml.getBytes(UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static int otherOwnGroup(int aCurrent) throws IOException {
        Process process = new ProcessBuilder("id", "-G").start();
        String  output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), UTF_8).trim();
        }
        for (String gid : output.split("\\s+")) {
            if (!gid.isEmpty() && Integer.parseInt(gid) != aCurrent) {
                return Integer.parseInt(gid);
            }
        }
        Assume.assumeTrue("needs a second own group", false);
        return -1;
    }

    /** Delegates and records applyOwner calls. */
    private class CountingFileSystem implements IFileSystem {

        private final IFileSystem delegate;

        CountingFileSystem(IFileSystem aDelegate) {
            delegate = aDelegate;
        }

        @Override
        public void writeFileWithMode(File aFile, byte[] aBody, String aMode) {
            delegate.writeFileWithMode(aFile, aBody, aMode);
        }

        @Override
        public void deleteFileIfExists(File aFile) {
            delegate.deleteFileIfExists(aFile);
        }

        @Override
        public void createDirectories(Owner aOwner, File aDir) {
            delegate.createDirectories(aOwner, aDir);
        }

        @Override
        public void writeExecutable(Owner aOwner, File aFile, String aText) {
            delegate.writeExecutable(aOwner, aFile, aText);
        }

        @Override
        public void copyDir(Owner aOwner, File aFrom, File aTo) {
            delegate.copyDir(aOwner, aFrom, aTo);
        }

        @Override
        public void copyFile(Owner aOwner, File aFrom, File aTo) {
            delegate.copyFile(aOwner, aFrom, aTo);
        }

        @Override
        public void writeFile(Owner aOwner, File aSource, byte[] body) {
            delegate.writeFile(aOwner, aSource, body);
        }

        @Override
        public void copyTemplateFile(Owner aOwner, File aFrom, File aTo, List<BoundVariable> aVariables) {
            delegate.copyTemplateFile(aOwner, aFrom, aTo, aVariables);
        }

        @Override
        public void applyOwner(File aDir, TVolumeOwner aResolvedOwner, String aMode) {
            applied.add(aDir.getPath());
            delegate.applyOwner(aDir, aResolvedOwner, aMode);
        }
    }
}
