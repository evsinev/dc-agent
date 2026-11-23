package com.payneteasy.dcagent.systemd;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

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
    public void chmod_refuses_a_link() throws Exception {
        Path target = Files.createFile(tmp.getRoot().toPath().toAbsolutePath().resolve("target"));
        Path link   = Files.createSymbolicLink(tmp.getRoot().toPath().toAbsolutePath().resolve("link"), target);

        assertThatThrownBy(() -> new HostActions.Real().chmod(link, "rwxrwxrwx")).hasMessageContaining("is a link");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isNotEqualTo("rwxrwxrwx");
    }
}
