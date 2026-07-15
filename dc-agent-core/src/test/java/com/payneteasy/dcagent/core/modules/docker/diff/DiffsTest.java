package com.payneteasy.dcagent.core.modules.docker.diff;

import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.helpers.MessageFormatter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DiffsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final List<String> messages = new ArrayList<>();
    private final IActionLogger logger = (pattern, args) ->
            messages.add(MessageFormatter.arrayFormat(pattern, args).getMessage());

    private File fileWith(String name, String content) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void file_diff_emits_lines_for_differing_files() throws Exception {
        File from = fileWith("from.txt", "line-a\nline-b\n");
        File to = fileWith("to.txt", "line-a\nCHANGED\n");

        Diffs.logDiff(logger, from, to);

        assertThat(messages).isNotEmpty();
    }

    @Test
    public void file_diff_emits_nothing_for_identical_files() throws Exception {
        File from = fileWith("from.txt", "same\ncontent\n");
        File to = fileWith("to.txt", "same\ncontent\n");

        Diffs.logDiff(logger, from, to);

        assertThat(messages).isEmpty();
    }

    @Test
    public void file_diff_is_noop_when_source_missing() throws Exception {
        File to = fileWith("to.txt", "content\n");

        Diffs.logDiff(logger, new File(folder.getRoot(), "absent.txt"), to);

        assertThat(messages).isEmpty();
    }

    @Test
    public void bytes_diff_is_noop_when_target_missing() {
        Diffs.logDiff(logger, "whatever".getBytes(StandardCharsets.UTF_8), new File(folder.getRoot(), "absent.txt"));

        assertThat(messages).isEmpty();
    }

    @Test
    public void bytes_diff_emits_lines_against_existing_target() throws Exception {
        File to = fileWith("to.txt", "old-content\n");

        Diffs.logDiff(logger, "new-content\n".getBytes(StandardCharsets.UTF_8), to);

        assertThat(messages).isNotEmpty();
    }
}
