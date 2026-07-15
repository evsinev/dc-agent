package com.payneteasy.dcagent.core.modules.jar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class LastLogLinesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File logWith(String content) throws Exception {
        File file = folder.newFile("log.txt");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void keeps_only_the_last_n_lines_in_order() throws Exception {
        File file = logWith("l1\nl2\nl3\nl4\nl5\n");
        StringBuilder sb = new StringBuilder();

        new LastLogLines(file, 3).showLastLines(sb);

        assertThat(sb.toString()).isEqualTo("l3\nl4\nl5\n");
    }

    @Test
    public void returns_all_lines_when_file_has_fewer_than_n() throws Exception {
        File file = logWith("a\nb\n");
        StringBuilder sb = new StringBuilder();

        new LastLogLines(file, 5).showLastLines(sb);

        assertThat(sb.toString()).isEqualTo("a\nb\n");
    }

    @Test
    public void returns_empty_for_empty_file() throws Exception {
        File file = logWith("");
        StringBuilder sb = new StringBuilder();

        new LastLogLines(file, 3).showLastLines(sb);

        assertThat(sb.toString()).isEmpty();
    }
}
