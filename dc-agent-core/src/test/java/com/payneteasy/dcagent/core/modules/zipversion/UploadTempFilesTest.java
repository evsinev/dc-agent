package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UploadTempFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path            dir;
    private UploadTempFiles uploads;

    @Before
    public void setUp() throws IOException {
        dir     = tmp.newFolder("tmp").toPath();
        uploads = new UploadTempFiles(dir);
    }

    @Test
    public void an_upload_is_owner_only_and_gone_when_closed() throws IOException {
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path file;
        try (UploadTempFiles.Upload upload = uploads.create()) {
            file = upload.path();
            upload.copyFrom(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
            assertThat(file.getFileName().toString()).startsWith(UploadTempFiles.PREFIX);
            assertThat(Files.size(file)).isEqualTo(3);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
        assertThat(file).doesNotExist();
    }

    @Test
    public void one_byte_over_the_limit_is_a_413() throws IOException {
        try (UploadTempFiles.Upload upload = uploads.create()) {
            assertThatThrownBy(() -> upload.copyFrom(new ByteArrayInputStream(new byte[11]), 10))
                    .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.getHttpCode()).isEqualTo(413));
        }
        try (Stream<Path> list = Files.list(dir)) {
            assertThat(list).isEmpty();
        }
    }

    @Test
    public void sweep_removes_only_old_regular_files_with_the_prefix() throws IOException {
        Path old      = Files.writeString(dir.resolve(UploadTempFiles.PREFIX + "old.zip"), "x");
        Path young    = Files.writeString(dir.resolve(UploadTempFiles.PREFIX + "young.zip"), "x");
        Path foreign  = Files.writeString(dir.resolve("other-old.zip"), "x");
        Path target   = Files.writeString(tmp.newFile().toPath(), "keep");
        Path link     = Files.createSymbolicLink(dir.resolve(UploadTempFiles.PREFIX + "link.zip"), target);
        Path oldDir   = Files.createDirectory(dir.resolve(UploadTempFiles.PREFIX + "dir"));
        FileTime twoDaysAgo = FileTime.from(Instant.now().minus(Duration.ofDays(2)));
        for (Path path : new Path[]{old, foreign, target, oldDir}) {
            Files.setLastModifiedTime(path, twoDaysAgo);
        }

        assertThat(uploads.sweep(Duration.ofDays(1))).isEqualTo(1);

        assertThat(old).doesNotExist();
        assertThat(young).exists();
        assertThat(foreign).exists();
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(target).hasContent("keep");
        assertThat(oldDir).isDirectory();
    }
}
