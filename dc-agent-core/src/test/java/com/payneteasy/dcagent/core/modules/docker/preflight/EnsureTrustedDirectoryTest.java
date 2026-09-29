package com.payneteasy.dcagent.core.modules.docker.preflight;

import com.sun.security.auth.module.UnixSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The agent's temp root: created and checked before a task is extracted into it. */
public class EnsureTrustedDirectoryTest {

    private static final int ME       = (int) new UnixSystem().getUid();
    private static final int STRANGER = 54321;
    private static final int STICKY   = 01000;

    private Path base;

    @Before
    public void createBase() throws IOException {
        base = Files.createTempDirectory("trusted-dir").toRealPath();
    }

    @After
    public void deleteBase() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void creates_missing_components_private_and_returns_the_physical_path() throws IOException {
        Path dir = base.resolve("a/b/tmp");

        File physical = preflight(PathAttributes.LSTAT).ensureTrustedDirectory("TEMP_DIR", dir.toFile());

        assertThat(physical.toPath()).isEqualTo(dir);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(base.resolve("a")))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
    }

    @Test
    public void creates_more_missing_components_than_the_configured_path_has() throws IOException {
        // TEMP_DIR=<base>/t, t -> <base>/v/d0/d1/...: more directories to create than <base>/t has names
        Path link    = base.resolve("t");
        Path target  = Files.createDirectory(base.resolve("v"));
        int  missing = link.getNameCount() + 3;
        for (int i = 0; i < missing; i++) {
            target = target.resolve("d" + i);
        }
        Files.createSymbolicLink(link, target);

        File physical = preflight(PathAttributes.LSTAT).ensureTrustedDirectory("TEMP_DIR", link.toFile());

        assertThat(physical.toPath()).isEqualTo(target);
        assertThat(target).isDirectory();
    }

    @Test
    public void accepts_an_existing_trusted_directory_through_a_link() throws IOException {
        Path real = Files.createDirectory(base.resolve("real"));
        Path link = Files.createSymbolicLink(base.resolve("link"), real);

        File physical = preflight(PathAttributes.LSTAT).ensureTrustedDirectory("TEMP_DIR", link.toFile());

        assertThat(physical.toPath()).isEqualTo(real);
    }

    @Test
    public void refuses_a_final_directory_writable_by_others() throws IOException {
        Path dir = Files.createDirectory(base.resolve("open"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> preflight(PathAttributes.LSTAT).ensureTrustedDirectory("TEMP_DIR", dir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("directory " + dir + " (uid " + ME + ", mode 0777)");
    }

    @Test
    public void accepts_a_sticky_final_directory_writable_by_others() throws IOException {
        Path dir = Files.createDirectory(base.resolve("sticky"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));

        File physical = preflight(withSticky(dir)).ensureTrustedDirectory("TEMP_DIR", dir.toFile());

        assertThat(physical.toPath()).isEqualTo(dir);
    }

    @Test
    public void creates_a_missing_directory_in_a_sticky_parent() throws IOException {
        // like TEMP_DIR=/tmp/dc-agent on a first start
        Path sticky = Files.createDirectory(base.resolve("sticky"));
        Files.setPosixFilePermissions(sticky, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path dir = sticky.resolve("dc-agent");

        preflight(withSticky(sticky)).ensureTrustedDirectory("TEMP_DIR", dir.toFile());

        assertThat(dir).isDirectory();
    }

    @Test
    public void refuses_to_create_in_a_parent_writable_by_others_without_sticky() throws IOException {
        Path open = Files.createDirectory(base.resolve("open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path dir = open.resolve("tmp");

        assertThatThrownBy(() -> preflight(PathAttributes.LSTAT).ensureTrustedDirectory("TEMP_DIR", dir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot create " + dir);

        assertThat(dir).doesNotExist();
    }

    @Test
    public void does_not_create_through_a_link_in_a_strangers_directory() throws IOException {
        // TEMP_DIR=<base>/u/work/cache where a local user owns u and put work -> outside into it
        Path outside = Files.createDirectory(base.resolve("outside"));
        Path u       = Files.createDirectory(base.resolve("u"));
        Files.createSymbolicLink(u.resolve("work"), outside);

        assertThatThrownBy(() -> preflight(ownedBy(u, STRANGER)).ensureTrustedDirectory("TEMP_DIR", u.resolve("work/cache").toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("directory " + u + " (uid " + STRANGER);

        assertThat(outside.resolve("cache")).doesNotExist();
    }

    @Test
    public void refuses_dot_dot() {
        assertThatThrownBy(() -> preflight(PathAttributes.LSTAT).ensureTrustedDirectory("TEMP_DIR", new File(base.toFile(), "a/../tmp")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'.' and '..' are not allowed");
    }

    private static WritePathPreflight preflight(PathAttributes.Reader aReader) {
        return new WritePathPreflight(Map.of(), WritePathPreflight.trustedUids(ME), aReader);
    }

    private static PathAttributes.Reader withSticky(Path aDir) {
        return path -> {
            PathAttributes real = PathAttributes.LSTAT.read(path);
            if (real == null || !path.equals(aDir)) {
                return real;
            }
            return new PathAttributes(real.uid(), real.gid(), 0040000 | STICKY | real.permissions(), 1, path.hashCode());
        };
    }

    private static PathAttributes.Reader ownedBy(Path aDir, int aUid) {
        return path -> {
            PathAttributes real = PathAttributes.LSTAT.read(path);
            if (real == null || !path.equals(aDir)) {
                return real;
            }
            return new PathAttributes(aUid, real.gid(), 0040000 | real.permissions(), 1, path.hashCode());
        };
    }
}
