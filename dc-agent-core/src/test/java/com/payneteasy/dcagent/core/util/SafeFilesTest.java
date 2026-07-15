package com.payneteasy.dcagent.core.util;

import com.payneteasy.dcagent.core.exception.HttpProblemException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.payneteasy.dcagent.core.util.SafeFiles.createFileGuarded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SafeFilesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void create_file_guarded() throws IOException {
        assertThat(createFileGuarded(new File("./target"), "test.txt"))
                .isEqualTo(new File("./target/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "/test.txt"))
                .isEqualTo(new File("./target/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "dir1/test.txt"))
                .isEqualTo(new File("./target/dir1/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "/dir1/test.txt"))
                .isEqualTo(new File("./target/dir1/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "dir1/dir2/../test.txt").getCanonicalFile())
                .isEqualTo(new File("./target/dir1/test.txt").getCanonicalFile());

        assertThat(createFileGuarded(new File("./target"), "dir1/dir2/dir3/../../test.txt").getCanonicalFile())
                .isEqualTo(new File("./target/dir1/test.txt").getCanonicalFile());

        assertThatThrownBy(() -> createFileGuarded(new File("./target"), "../test.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal attempt detected:");
    }

    @Test
    public void list_files_returns_matching_files() throws IOException {
        folder.newFile("a.txt");
        folder.newFile("b.txt");

        assertThat(SafeFiles.listFiles(folder.getRoot(), f -> true)).hasSize(2);
    }

    @Test
    public void list_files_of_non_directory_returns_empty() {
        File notADir = new File(folder.getRoot(), "missing");

        assertThat(SafeFiles.listFiles(notADir, f -> true)).isEmpty();
    }

    @Test
    public void read_file_returns_content_with_trailing_newline_per_line() throws IOException {
        File file = folder.newFile("data.txt");
        Files.write(file.toPath(), "first\nsecond".getBytes(StandardCharsets.UTF_8));

        assertThat(SafeFiles.readFile(file)).isEqualTo("first\nsecond\n");
    }

    @Test
    public void read_file_of_missing_file_throws_unchecked_io() {
        assertThatThrownBy(() -> SafeFiles.readFile(new File(folder.getRoot(), "nope.txt")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    public void write_file_creates_parent_directories() {
        File target = new File(folder.getRoot(), "sub/dir/out.bin");

        SafeFiles.writeFile(target, new byte[]{1, 2, 3});

        assertThat(target).exists();
    }

    @Test
    public void write_file_stores_the_given_bytes() throws IOException {
        File target = new File(folder.getRoot(), "out.bin");

        SafeFiles.writeFile(target, new byte[]{9, 8, 7});

        assertThat(Files.readAllBytes(target.toPath())).containsExactly(9, 8, 7);
    }

    @Test
    public void write_file_from_stream_copies_content() throws IOException {
        File target = new File(folder.getRoot(), "streamed.txt");

        SafeFiles.writeFile(target, new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));

        assertThat(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)).isEqualTo("payload");
    }

    @Test
    public void create_dirs_creates_missing_directory() {
        File dir = new File(folder.getRoot(), "x/y/z");

        SafeFiles.createDirs(dir);

        assertThat(dir).isDirectory();
    }

    @Test
    public void create_dirs_returns_existing_directory_unchanged() {
        assertThat(SafeFiles.createDirs(folder.getRoot())).isEqualTo(folder.getRoot());
    }

    @Test
    public void delete_file_with_warning_removes_the_file() throws IOException {
        File file = folder.newFile("to-delete.txt");

        SafeFiles.deleteFileWithWarning(file, "test");

        assertThat(file).doesNotExist();
    }

    @Test
    public void ensure_file_exists_returns_the_file() throws IOException {
        File file = folder.newFile("present.txt");

        assertThat(SafeFiles.ensureFileExists(file)).isEqualTo(file);
    }

    @Test
    public void ensure_file_exists_rejects_a_directory() {
        assertThatThrownBy(() -> SafeFiles.ensureFileExists(folder.getRoot()))
                .isInstanceOf(HttpProblemException.class);
    }

    @Test
    public void ensure_file_exists_rejects_a_missing_file() {
        assertThatThrownBy(() -> SafeFiles.ensureFileExists(new File(folder.getRoot(), "absent.txt")))
                .isInstanceOf(HttpProblemException.class);
    }

    @Test
    public void ensure_dir_exists_returns_the_directory() {
        assertThat(SafeFiles.ensureDirExists(folder.getRoot())).isEqualTo(folder.getRoot());
    }

    @Test
    public void ensure_dir_exists_rejects_a_regular_file() throws IOException {
        File file = folder.newFile("a-file.txt");

        assertThatThrownBy(() -> SafeFiles.ensureDirExists(file))
                .isInstanceOf(HttpProblemException.class);
    }

    @Test
    public void ensure_dir_exists_rejects_a_missing_directory() {
        assertThatThrownBy(() -> SafeFiles.ensureDirExists(new File(folder.getRoot(), "absent-dir")))
                .isInstanceOf(HttpProblemException.class);
    }
}
