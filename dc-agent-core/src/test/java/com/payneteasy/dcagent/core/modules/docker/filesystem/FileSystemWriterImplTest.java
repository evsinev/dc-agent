package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class FileSystemWriterImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final IActionLogger logger = (pattern, args) -> { };
    private final FileSystemWriterImpl fs = new FileSystemWriterImpl(logger);

    @Test
    public void create_directories_creates_missing_directory() {
        File dir = new File(folder.getRoot(), "a/b");

        fs.createDirectories(null, dir);

        assertThat(dir).isDirectory();
    }

    @Test
    public void write_file_stores_bytes() throws Exception {
        File file = new File(folder.getRoot(), "out.bin");

        fs.writeFile(null, file, new byte[]{1, 2, 3});

        assertThat(Files.readAllBytes(file.toPath())).containsExactly(1, 2, 3);
    }

    @Test
    public void write_executable_sets_owner_execute_permission() throws Exception {
        File file = new File(folder.getRoot(), "run");

        fs.writeExecutable(null, file, "#!/bin/sh\n");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.toPath());
        assertThat(perms).contains(PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    public void copy_file_copies_content() throws Exception {
        File from = new File(folder.getRoot(), "src.txt");
        Files.write(from.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        File to = new File(folder.getRoot(), "dst.txt");

        fs.copyFile(null, from, to);

        assertThat(new String(Files.readAllBytes(to.toPath()), StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    public void copy_dir_copies_nested_tree() throws Exception {
        File from = folder.newFolder("from");
        Files.write(new File(from, "top.txt").toPath(), "t".getBytes(StandardCharsets.UTF_8));
        File sub = new File(from, "sub");
        assertThat(sub.mkdirs()).isTrue();
        Files.write(new File(sub, "deep.txt").toPath(), "d".getBytes(StandardCharsets.UTF_8));

        File to = new File(folder.getRoot(), "to");
        fs.copyDir(null, from, to);

        assertThat(new File(to, "sub/deep.txt")).exists();
    }

    @Test
    public void copy_template_file_substitutes_variables() throws Exception {
        File from = new File(folder.getRoot(), "tpl.txt");
        Files.write(from.toPath(), "hello {{NAME}}".getBytes(StandardCharsets.UTF_8));
        File to = new File(folder.getRoot(), "rendered.txt");

        fs.copyTemplateFile(null, from, to, List.of(BoundVariable.builder().name("NAME").value("world").build()));

        assertThat(new String(Files.readAllBytes(to.toPath()), StandardCharsets.UTF_8)).startsWith("hello world");
    }

    @Test
    public void write_file_is_skipped_when_content_is_identical() throws Exception {
        File file = new File(folder.getRoot(), "same.bin");
        fs.writeFile(null, file, new byte[]{5, 5});
        long firstModified = file.lastModified();

        fs.writeFile(null, file, new byte[]{5, 5});

        assertThat(file.lastModified()).isEqualTo(firstModified);
    }
}
