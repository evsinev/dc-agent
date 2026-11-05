package com.payneteasy.dcagent.core.util.zip;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeFalse;

public class ZipDirCreateMoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void zips_files_from_a_directory_tree_with_first_segment_prefix() throws Exception {
        File base = folder.newFolder("base");
        Files.write(new File(base, "top.txt").toPath(), "top".getBytes(StandardCharsets.UTF_8));
        File sub = new File(base, "sub");
        assertThat(sub.mkdirs()).isTrue();
        Files.write(new File(sub, "deep.txt").toPath(), "deep".getBytes(StandardCharsets.UTF_8));

        File zip = new File(folder.getRoot(), "out.zip");
        new ZipDirCreate().baseDir(base).firstSegment("root").createZipFile(zip);

        ZipFileViewer viewer = new ZipFileViewer(zip);
        assertThat(viewer.getItemText("root/top.txt")).isEqualTo("top");
        assertThat(viewer.getItemText("root/sub/deep.txt")).isEqualTo("deep");
    }

    @Test
    public void requires_a_base_dir() {
        File zip = new File(folder.getRoot(), "out.zip");

        assertThatThrownBy(() -> new ZipDirCreate().createZipFile(zip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void keeps_the_entry_names_without_a_first_segment() throws Exception {
        File base = folder.newFolder("plain");
        Files.write(new File(base, "dc-docker.yml").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertThat(new File(base, "conf").mkdirs()).isTrue();
        Files.write(new File(base, "conf/a.conf").toPath(), "a".getBytes(StandardCharsets.UTF_8));

        File zip = new File(folder.getRoot(), "plain.zip");
        new ZipDirCreate().baseDir(base).createZipFile(zip);

        try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip)) {
            assertThat(file.stream().map(java.util.zip.ZipEntry::getName)).containsExactly("/conf/a.conf", "/dc-docker.yml");
        }
    }

    @Test
    public void an_unreadable_file_or_directory_fails_with_every_path_and_writes_nothing() throws Exception {
        assumeFalse("root reads files without the read permission", isRoot());
        File base = folder.newFolder("task");
        Files.write(new File(base, "ok.conf").toPath(), "ok".getBytes(StandardCharsets.UTF_8));
        File pki = new File(base, "pki");
        assertThat(pki.mkdirs()).isTrue();
        Path key = Files.write(new File(pki, "site-a.key").toPath(), "key".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("-w-------"));
        File closed = new File(base, "closed");
        assertThat(closed.mkdirs()).isTrue();
        Files.write(new File(closed, "inside").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(closed.toPath(), PosixFilePermissions.fromString("-wx------"));
        try {
            File zip = new File(folder.getRoot(), "task.zip");

            assertThatThrownBy(() -> new ZipDirCreate().baseDir(base).createZipFile(zip))
                    .isInstanceOf(UnreadableTaskFilesException.class)
                    .hasMessageContaining("closed/ (cannot list: AccessDeniedException)")
                    .hasMessageContaining("pki/site-a.key (cannot read)");
            assertThat(zip).doesNotExist();
        } finally {
            Files.setPosixFilePermissions(closed.toPath(), PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    public void a_dangling_symbolic_link_fails() throws Exception {
        File base = folder.newFolder("dangling");
        Files.createSymbolicLink(new File(base, "gone.conf").toPath(), new File(base, "nowhere").toPath());

        assertThatThrownBy(() -> new ZipDirCreate().baseDir(base).createZipFile(new File(folder.getRoot(), "d.zip")))
                .isInstanceOf(UnreadableTaskFilesException.class)
                .hasMessageContaining("gone.conf (dangling symbolic link)");
    }

    @Test
    public void a_listing_or_read_failure_with_permissions_in_place_is_reported_for_every_path_and_the_output_is_untouched() throws Exception {
        File base = folder.newFolder("eio");
        Files.write(new File(base, "a.conf").toPath(), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(base, "b.conf").toPath(), "b".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(base, "c.conf").toPath(), "c".getBytes(StandardCharsets.UTF_8));
        File sub = new File(base, "sub");
        assertThat(sub.mkdirs()).isTrue();
        Files.write(new File(sub, "inside").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File zip = new File(folder.getRoot(), "eio.zip");
        Files.write(zip.toPath(), "previous".getBytes(StandardCharsets.UTF_8));

        // readable by permissions, but the file system fails (e.g. EIO on a network mount)
        ZipDirCreate.Io failing = new ZipDirCreate.Io() {
            @Override
            public java.util.List<Path> list(Path aDir) throws java.io.IOException {
                if (aDir.getFileName().toString().equals("sub")) {
                    throw new java.io.IOException("EIO");
                }
                try (java.util.stream.Stream<Path> entries = Files.list(aDir)) {
                    return entries.toList();
                }
            }

            @Override
            public void copy(Path aFile, java.io.OutputStream aOut) throws java.io.IOException {
                if (!aFile.getFileName().toString().equals("a.conf")) {
                    throw new java.io.IOException("EIO");
                }
                Files.copy(aFile, aOut);
            }
        };

        assertThatThrownBy(() -> new ZipDirCreate().baseDir(base).io(failing).createZipFile(zip))
                .isInstanceOf(UnreadableTaskFilesException.class)
                .hasMessageContaining("b.conf (cannot read: IOException)")
                .hasMessageContaining("c.conf (cannot read: IOException)")
                .hasMessageContaining("sub/ (cannot list: IOException)");
        assertThat(zip).hasContent("previous");
        assertThat(folder.getRoot().list()).noneMatch(name -> name.endsWith(".partial"));
    }

    private static boolean isRoot() {
        return new com.sun.security.auth.module.UnixSystem().getUid() == 0;
    }
}
