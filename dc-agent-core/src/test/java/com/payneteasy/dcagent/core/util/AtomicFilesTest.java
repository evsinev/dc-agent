package com.payneteasy.dcagent.core.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class AtomicFilesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path dir() {
        return folder.getRoot().toPath();
    }

    @Test
    public void a_new_file_is_0600_and_an_existing_target_is_never_replaced() throws Exception {
        Path target = dir().resolve("site.json");

        assertThat(AtomicFiles.createNew(target, "first".getBytes(UTF_8))).isTrue();
        assertThat(AtomicFiles.createNew(target, "second".getBytes(UTF_8))).isFalse();

        assertThat(target).hasContent("first");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
        assertNoTemporaryFile();
    }

    @Test
    public void a_replacement_keeps_the_mode_of_the_file_it_replaces() throws Exception {
        Path target = Files.writeString(dir().resolve("site.json"), "old");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));

        AtomicFiles.replace(target, "new".getBytes(UTF_8));

        assertThat(target).hasContent("new");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-r-----");
        assertNoTemporaryFile();
    }

    @Test
    public void a_replacement_takes_the_mode_of_another_file_when_asked() throws Exception {
        Path yaml = Files.writeString(dir().resolve("site.yml"), "old");
        Files.setPosixFilePermissions(yaml, PosixFilePermissions.fromString("rw-r-----"));
        Path json = dir().resolve("site.json");

        AtomicFiles.replace(json, "new".getBytes(UTF_8), yaml);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(json))).isEqualTo("rw-r-----");
    }

    @Test
    public void a_link_at_the_target_is_replaced_not_followed() throws Exception {
        Path outside = Files.writeString(folder.newFolder("outside").toPath().resolve("cron"), "original");
        Path target  = Files.createSymbolicLink(dir().resolve("site.json"), outside);

        AtomicFiles.replace(target, "new".getBytes(UTF_8));

        assertThat(outside).hasContent("original");
        assertThat(Files.isSymbolicLink(target)).isFalse();
    }

    private void assertNoTemporaryFile() throws Exception {
        try (Stream<Path> files = Files.list(dir())) {
            assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".dc-agent-tmp-"));
        }
    }
}
