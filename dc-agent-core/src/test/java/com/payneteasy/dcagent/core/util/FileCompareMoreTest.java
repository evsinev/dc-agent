package com.payneteasy.dcagent.core.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class FileCompareMoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File file(String name, String content) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void identical_files_are_equal() throws Exception {
        assertThat(FileCompare.isFileIdentical(file("a", "hello"), file("b", "hello"))).isTrue();
    }

    @Test
    public void files_of_different_length_are_not_equal() throws Exception {
        assertThat(FileCompare.isFileIdentical(file("a", "hello"), file("b", "hi"))).isFalse();
    }

    @Test
    public void files_of_same_length_but_different_content_are_not_equal() throws Exception {
        assertThat(FileCompare.isFileIdentical(file("a", "abc"), file("b", "abd"))).isFalse();
    }

    @Test
    public void missing_file_is_not_equal_to_another() throws Exception {
        assertThat(FileCompare.isFileIdentical(new File(folder.getRoot(), "missing"), file("b", "x"))).isFalse();
    }

    @Test
    public void file_matches_identical_bytes() throws Exception {
        assertThat(FileCompare.isFileIdentical(file("a", "hello"), "hello".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    public void file_does_not_match_bytes_of_different_length() throws Exception {
        assertThat(FileCompare.isFileIdentical(file("a", "hello"), "hi".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    public void file_does_not_match_bytes_of_different_content() throws Exception {
        assertThat(FileCompare.isFileIdentical(file("a", "abc"), "abd".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    public void missing_file_does_not_match_bytes() {
        assertThat(FileCompare.isFileIdentical(new File(folder.getRoot(), "missing"), new byte[]{1})).isFalse();
    }
}
