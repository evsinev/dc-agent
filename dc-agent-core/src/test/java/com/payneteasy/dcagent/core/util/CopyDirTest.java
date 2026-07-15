package com.payneteasy.dcagent.core.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CopyDirTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File write(File dir, String name, String content) throws Exception {
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void copy_file_copies_content() throws Exception {
        File from = write(folder.getRoot(), "src.txt", "hello");
        File to = new File(folder.getRoot(), "dst.txt");

        CopyDir.copyFile(from, to);

        assertThat(new String(Files.readAllBytes(to.toPath()), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    public void copy_file_overwrites_existing_target() throws Exception {
        File from = write(folder.getRoot(), "src.txt", "new");
        File to = write(folder.getRoot(), "dst.txt", "old");

        CopyDir.copyFile(from, to);

        assertThat(new String(Files.readAllBytes(to.toPath()), StandardCharsets.UTF_8)).isEqualTo("new");
    }

    @Test
    public void copy_file_missing_source_throws_illegal_state() {
        File missing = new File(folder.getRoot(), "absent.txt");
        File to = new File(folder.getRoot(), "dst.txt");

        assertThatThrownBy(() -> CopyDir.copyFile(missing, to))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void copy_dir_copies_nested_tree() throws Exception {
        File from = folder.newFolder("from");
        File nested = new File(from, "sub");
        assertThat(nested.mkdirs()).isTrue();
        write(from, "top.txt", "top");
        write(nested, "deep.txt", "deep");

        File to = new File(folder.getRoot(), "to");
        CopyDir.copyDir(from, to);

        assertThat(new File(to, "top.txt")).exists();
        assertThat(new File(to, "sub/deep.txt")).exists();
    }
}
