package com.payneteasy.dcagent.core.modules.docker.preflight;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PathWalkerTest {

    private final PathWalker walker = new PathWalker(PathAttributes.LSTAT);

    private Path base;

    @Before
    public void createBase() throws IOException {
        base = Files.createTempDirectory("path-walker").toRealPath();
    }

    @After
    public void deleteBase() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void plain_directories() throws IOException {
        Path dir = Files.createDirectories(base.resolve("a/b"));

        PathWalker.Walk walk = walker.walk(dir, false);

        assertThat(walk.exists()).isTrue();
        assertThat(walk.physicalPath()).isEqualTo(dir);
        assertThat(walk.directories()).contains(base, base.resolve("a"), dir);
        assertThat(walk.steps().get(walk.steps().size() - 1).isLast()).isTrue();
    }

    /** toRealPath() would only show where the path ends; the walk also shows it went through state. */
    @Test
    public void records_directories_passed_through_a_link() throws IOException {
        Path state  = Files.createDirectories(base.resolve("state"));
        Path config = Files.createDirectories(base.resolve("config"));
        Files.createSymbolicLink(state.resolve("out"), config);

        PathWalker.Walk walk = walker.walk(state.resolve("out/file.yml"), true);

        assertThat(walk.physicalPath()).isEqualTo(config.resolve("file.yml"));
        assertThat(walk.directories()).contains(state, config);
        assertThat(walk.exists()).isFalse();
    }

    @Test
    public void relative_link_with_dot_dot() throws IOException {
        Path a = Files.createDirectories(base.resolve("a"));
        Path b = Files.createDirectories(base.resolve("b"));
        Files.createSymbolicLink(a.resolve("toB"), Path.of("../b"));

        PathWalker.Walk walk = walker.walk(a.resolve("toB"), true);

        assertThat(walk.physicalPath()).isEqualTo(b);
        assertThat(walk.directories()).contains(a, b);
    }

    @Test
    public void final_link_is_not_followed_when_asked() throws IOException {
        Path target = Files.createDirectories(base.resolve("target"));
        Path link   = Files.createSymbolicLink(base.resolve("link"), target);

        PathWalker.Walk walk = walker.walk(link, false);

        assertThat(walk.isFinalSymbolicLink()).isTrue();
        assertThat(walk.physicalPath()).isEqualTo(link);
    }

    @Test
    public void missing_tail_is_kept() throws IOException {
        PathWalker.Walk walk = walker.walk(base.resolve("x/y/z"), false);

        assertThat(walk.exists()).isFalse();
        assertThat(walk.physicalPath()).isEqualTo(base.resolve("x/y/z"));
        assertThat(walk.steps().get(walk.steps().size() - 1).childAttributes()).isNull();
    }

    @Test
    public void link_loop_fails() throws IOException {
        Files.createSymbolicLink(base.resolve("l1"), base.resolve("l2"));
        Files.createSymbolicLink(base.resolve("l2"), base.resolve("l1"));

        assertThatThrownBy(() -> walker.walk(base.resolve("l1/x"), true))
                .hasMessageContaining("Too many symbolic links");
    }

    @Test
    public void path_through_a_file_fails() throws IOException {
        Files.createFile(base.resolve("file"));

        assertThatThrownBy(() -> walker.walk(base.resolve("file/x"), true))
                .hasMessageContaining("Not a directory");
    }
}
