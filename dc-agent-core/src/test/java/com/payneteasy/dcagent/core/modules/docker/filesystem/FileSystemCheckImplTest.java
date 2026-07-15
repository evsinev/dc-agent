package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class FileSystemCheckImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final List<String> messages = new ArrayList<>();
    private final IActionLogger logger = (pattern, args) -> messages.add(pattern);
    private final FileSystemCheckImpl fs = new FileSystemCheckImpl(logger);

    @Test
    public void create_directories_does_not_create_but_logs() {
        File dir = new File(folder.getRoot(), "will-not-exist");

        fs.createDirectories(null, dir);

        assertThat(dir).doesNotExist();
        assertThat(messages).isNotEmpty();
    }

    @Test
    public void create_directories_is_silent_when_dir_exists() {
        fs.createDirectories(null, folder.getRoot());

        assertThat(messages).isEmpty();
    }

    @Test
    public void write_file_does_not_write_new_content() {
        File file = new File(folder.getRoot(), "planned.txt");

        fs.writeFile(null, file, "content".getBytes(StandardCharsets.UTF_8));

        assertThat(file).doesNotExist();
        assertThat(messages).isNotEmpty();
    }

    @Test
    public void write_file_is_silent_when_content_identical() throws Exception {
        File file = new File(folder.getRoot(), "same.txt");
        Files.write(file.toPath(), "same".getBytes(StandardCharsets.UTF_8));

        fs.writeFile(null, file, "same".getBytes(StandardCharsets.UTF_8));

        assertThat(messages).isEmpty();
    }

    @Test
    public void copy_template_file_does_not_write_but_logs() throws Exception {
        File from = new File(folder.getRoot(), "tpl.txt");
        Files.write(from.toPath(), "hello {{NAME}}".getBytes(StandardCharsets.UTF_8));
        File to = new File(folder.getRoot(), "rendered.txt");

        fs.copyTemplateFile(null, from, to, java.util.List.of(
                com.payneteasy.dcagent.core.config.model.docker.BoundVariable.builder().name("NAME").value("x").build()));

        assertThat(to).doesNotExist();
        assertThat(messages).isNotEmpty();
    }

    @Test
    public void write_executable_does_not_create_the_file() {
        File file = new File(folder.getRoot(), "run");

        fs.writeExecutable(null, file, "#!/bin/sh\n");

        assertThat(file).doesNotExist();
    }

    @Test
    public void copy_file_does_not_copy_but_logs_when_differing() throws Exception {
        File from = new File(folder.getRoot(), "src.txt");
        Files.write(from.toPath(), "new".getBytes(StandardCharsets.UTF_8));
        File to = new File(folder.getRoot(), "dst.txt");

        fs.copyFile(null, from, to);

        assertThat(to).doesNotExist();
        assertThat(messages).isNotEmpty();
    }
}
