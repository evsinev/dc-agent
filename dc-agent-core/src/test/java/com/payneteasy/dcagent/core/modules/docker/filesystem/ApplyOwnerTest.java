package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.security.TIdRef;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import com.sun.security.auth.module.UnixSystem;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * applyOwner of the writer (DOCKER_PUSH) and the checker (DOCKER_CHECK). Runs without root:
 * group and mode changes use the user's own groups; a uid change needs root.
 */
public class ApplyOwnerTest {

    private static final int ME = (int) new UnixSystem().getUid();

    private final List<String>  messages = new ArrayList<>();
    private final IActionLogger logger   = (aPattern, args) -> messages.add(MessageFormatter.arrayFormat(aPattern, args).getMessage());

    private Path base;
    private Path dir;

    @Before
    public void createDir() throws IOException {
        base = Files.createTempDirectory("apply-owner").toRealPath();
        dir  = Files.createDirectory(base.resolve("state"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @After
    public void deleteDir() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void writer_changes_mode() throws IOException {
        new FileSystemWriterImpl(logger).applyOwner(dir.toFile(), null, "0770");

        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwxrwx---"));
        assertThat(messages).containsExactly("🔑  Changing mode 0755 → 0770 of " + dir + " ...");
    }

    /** From the current group to another own group, so a missing chgrp cannot pass unnoticed. */
    @Test
    public void writer_changes_group() throws IOException {
        int current = gid(dir);
        int other   = otherOwnGroup(current);

        new FileSystemWriterImpl(logger).applyOwner(dir.toFile(), owner(null, other), null);

        assertThat(gid(dir)).isEqualTo(other);
        assertThat(messages).containsExactly("🔑  Changing gid " + current + " → " + other + " of " + dir + " ...");
    }

    @Test
    public void writer_changes_uid_as_root() throws IOException {
        Assume.assumeTrue("needs root", ME == 0);

        new FileSystemWriterImpl(logger).applyOwner(dir.toFile(), owner(4242, 4243), "0700");

        assertThat(Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS)).isEqualTo(4242);
        assertThat(Files.getAttribute(dir, "unix:gid", LinkOption.NOFOLLOW_LINKS)).isEqualTo(4243);
    }

    @Test
    public void writer_without_root_explains_the_failure() {
        Assume.assumeTrue("not as root", ME != 0);

        assertThatThrownBy(() -> new FileSystemWriterImpl(logger).applyOwner(dir.toFile(), owner(ME + 1, null), null))
                .hasMessageContainingAll("uid " + ME + " → " + (ME + 1), dir.toString(), "root");
    }

    @Test
    public void nothing_to_change() throws IOException {
        new FileSystemWriterImpl(logger).applyOwner(dir.toFile(), owner(ME, gid(dir)), "0755");
        new FileSystemCheckImpl(logger).applyOwner(dir.toFile(), owner(ME, gid(dir)), "755");

        assertThat(messages).isEmpty();
    }

    @Test
    public void symbolic_link_is_refused() throws IOException {
        Path link = Files.createSymbolicLink(base.resolve("link"), dir);

        assertThatThrownBy(() -> new FileSystemWriterImpl(logger).applyOwner(link.toFile(), null, "0700"))
                .hasMessageContaining("is not a directory");
        assertThatThrownBy(() -> new FileSystemCheckImpl(logger).applyOwner(link.toFile(), null, "0700"))
                .hasMessageContaining("is not a directory");
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    public void checker_reports_the_same_change_and_changes_nothing() throws IOException {
        int current = gid(dir);
        int other   = otherOwnGroup(current);

        new FileSystemCheckImpl(logger).applyOwner(dir.toFile(), owner(null, other), "0770");

        assertThat(messages).containsExactly("🔑  Will change gid " + current + " → " + other + ", mode 0755 → 0770 of " + dir);
        assertThat(gid(dir)).isEqualTo(current);
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    public void checker_on_a_directory_to_be_created() {
        Path missing = base.resolve("missing");

        new FileSystemCheckImpl(logger).applyOwner(missing.toFile(), owner(1001, 1002), "0770");

        assertThat(messages).containsExactly("🔑  Will set uid 1001, gid 1002, mode 0770 on " + missing);
    }

    private static TVolumeOwner owner(Integer aUid, Integer aGid) {
        return TVolumeOwner.builder()
                .user  ( aUid == null ? null : TIdRef.builder().id(aUid).build() )
                .group ( aGid == null ? null : TIdRef.builder().id(aGid).build() )
                .build();
    }

    private static int gid(Path aPath) throws IOException {
        return (Integer) Files.getAttribute(aPath, "unix:gid", LinkOption.NOFOLLOW_LINKS);
    }

    /** A supplementary group of the current user other than {@code aCurrent}; skips the test if there is none. */
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
}
