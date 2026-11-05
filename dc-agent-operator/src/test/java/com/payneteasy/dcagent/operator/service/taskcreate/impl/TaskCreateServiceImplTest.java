package com.payneteasy.dcagent.operator.service.taskcreate.impl;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.zip.UnreadableTaskFilesException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TaskCreateServiceImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void packs_a_readable_task() throws IOException {
        Path task = Files.createDirectories(folder.getRoot().toPath().resolve("tasks/readable-task"));
        Files.writeString(task.resolve("dc-docker.yml"), "x");

        try (TempFile zip = new TaskCreateServiceImpl(folder.getRoot().toPath().resolve("tasks").toFile()).createTaskZipFile("readable-task")) {
            assertThat(zip.getFile()).isFile();
        }
    }

    @Test
    public void a_failed_packing_leaves_no_temp_file() throws IOException {
        String name = "unreadable-task-" + System.nanoTime();
        Path   task = Files.createDirectories(folder.getRoot().toPath().resolve("tasks/" + name));
        Files.createSymbolicLink(task.resolve("gone.key"), task.resolve("nowhere"));

        assertThatThrownBy(() -> new TaskCreateServiceImpl(folder.getRoot().toPath().resolve("tasks").toFile()).createTaskZipFile(name))
                .isInstanceOf(UnreadableTaskFilesException.class);

        try (Stream<Path> temp = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(temp.filter(path -> path.getFileName().toString().startsWith("task-" + name))).isEmpty();
        }
    }
}
