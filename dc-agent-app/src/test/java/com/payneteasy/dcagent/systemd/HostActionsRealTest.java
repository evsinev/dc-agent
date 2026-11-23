package com.payneteasy.dcagent.systemd;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real changes on the file system, where they need no root: modes, never through a link, never blocking on a fifo. */
public class HostActionsRealTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder(new java.io.File("target"));

    @Test(timeout = 10_000)
    public void chmod_of_a_fifo_nobody_writes_to_does_not_block() throws Exception {
        Path fifo = tmp.getRoot().toPath().toAbsolutePath().resolve("ok");
        assertThat(new ProcessBuilder("mkfifo", "-m", "0600", fifo.toString()).inheritIO().start().waitFor()).isZero();

        new HostActions.Real().chmod(fifo, "rw--w----");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(fifo, java.nio.file.LinkOption.NOFOLLOW_LINKS))).isEqualTo("rw--w----");
    }

    @Test
    public void mkdir_and_create_set_the_mode_and_owner_and_never_replace_anything() throws Exception {
        HostActions actions = new HostActions.Real();
        String      me      = System.getProperty("user.name");
        Path        dir     = tmp.getRoot().toPath().toAbsolutePath().resolve("d");
        Path        file    = dir.resolve("env");

        actions.mkdir(dir, null, "rwx------");
        actions.create(file, null, "rw-------", "A=1\n"); // an owner also names its group: not on every build host

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(Files.readString(file)).isEqualTo("A=1\n");
        assertThat(Files.getOwner(file).getName()).isEqualTo(me);
        assertThatThrownBy(() -> actions.create(file, null, "rw-------", "B=2\n")).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        Path link = Files.createSymbolicLink(dir.resolve("link"), file);
        assertThatThrownBy(() -> actions.create(link, null, "rw-------", "B=2\n")).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThatThrownBy(() -> actions.mkdir(dir, null, "rwx------")).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).isEqualTo("A=1\n");
    }

    @Test
    public void chown_to_the_same_user_and_a_failing_tool_is_an_error() throws Exception {
        HostActions actions = new HostActions.Real();
        Path        file    = Files.createFile(tmp.getRoot().toPath().toAbsolutePath().resolve("f"));
        String      me      = System.getProperty("user.name");
        String      group   = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes.class).group().getName();

        actions.chown(file, me, group);

        assertThat(Files.getOwner(file).getName()).isEqualTo(me);
        assertThatThrownBy(() -> actions.setfacl("u:dc-agent-test-nobody-x:r--", List.of(file))).isInstanceOf(java.io.IOException.class);
        assertThat(actions.root()).isEqualTo("root".equals(me));
        assertThat(HostActions.Real.symbolic(0640)).isEqualTo("rw-r-----");
        assertThat(HostActions.Real.symbolic(0755)).isEqualTo("rwxr-xr-x");
    }

    @Test
    public void chmod_refuses_a_link() throws Exception {
        Path target = Files.createFile(tmp.getRoot().toPath().toAbsolutePath().resolve("target"));
        Path link   = Files.createSymbolicLink(tmp.getRoot().toPath().toAbsolutePath().resolve("link"), target);

        assertThatThrownBy(() -> new HostActions.Real().chmod(link, "rwxrwxrwx")).hasMessageContaining("is a link");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isNotEqualTo("rwxrwxrwx");
    }
}
