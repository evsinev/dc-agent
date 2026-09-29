package com.payneteasy.dcagent.core.util;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

/**
 * The root agent removes extracted tasks and deployed apps with this class: it must never delete
 * anything outside the sentinel directory, whatever links the tree contains. Every behaviour test
 * runs for both implementations: descriptor-relative (where the platform has it) and the path walk.
 */
@RunWith(Parameterized.class)
public class DeleteDirRecursivelyTest {

    @Parameterized.Parameters(name = "secure={0}")
    public static List<Boolean> implementations() {
        return List.of(true, false);
    }

    @Parameterized.Parameter
    public boolean secure;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path sentinel;
    private Path outside;
    private Path outsideMarker;

    @Before
    public void setUp() throws IOException {
        Path base = tmp.getRoot().toPath().toRealPath();
        sentinel      = Files.createDirectory(base.resolve("dc"));
        outside       = Files.createDirectory(base.resolve("outside"));
        outsideMarker = Files.writeString(outside.resolve("marker"), "keep");
    }

    @Test
    public void deletes_a_nested_tree() throws IOException {
        Path task = Files.createDirectories(sentinel.resolve("task/a/b"));
        Files.writeString(task.resolve("file"), "x");
        Files.writeString(sentinel.resolve("task/top"), "x");

        deleter().deleteDir(sentinel.resolve("task").toFile());

        assertThat(sentinel.resolve("task")).doesNotExist();
        assertThat(sentinel).exists();
    }

    @Test
    public void removes_a_link_to_an_outside_directory_not_its_content() throws IOException {
        Path task = Files.createDirectory(sentinel.resolve("task"));
        Files.createSymbolicLink(task.resolve("link"), outside);

        deleter().deleteDir(task.toFile());

        assertThat(task).doesNotExist();
        assertThat(outsideMarker).hasContent("keep");
    }

    @Test
    public void removes_a_link_to_an_outside_file_not_the_file() throws IOException {
        Path task = Files.createDirectory(sentinel.resolve("task"));
        Files.createSymbolicLink(task.resolve("link"), outsideMarker);

        deleter().deleteDir(task.toFile());

        assertThat(task).doesNotExist();
        assertThat(outsideMarker).hasContent("keep");
    }

    @Test
    public void removes_a_start_that_is_a_link_not_its_target() throws IOException {
        Path link = Files.createSymbolicLink(sentinel.resolve("task"), outside);

        deleter().deleteDir(link.toFile());

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(outsideMarker).hasContent("keep");
    }

    @Test
    public void removes_dangling_links() throws IOException {
        Path task = Files.createDirectory(sentinel.resolve("task"));
        Files.createSymbolicLink(task.resolve("dangling"), outside.resolve("missing"));
        Path danglingStart = Files.createSymbolicLink(sentinel.resolve("dangling-start"), outside.resolve("missing"));

        deleter().deleteDir(task.toFile());
        deleter().deleteDirIfExists(danglingStart.toFile());

        assertThat(task).doesNotExist();
        assertThat(Files.exists(danglingStart, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    public void skips_a_missing_start_but_deleteDir_fails_on_it() {
        File missing = sentinel.resolve("no/such/dir").toFile();

        deleter().deleteDirIfExists(missing);

        assertThatThrownBy(() -> deleter().deleteDir(missing)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void refuses_a_sibling_with_the_sentinel_as_a_string_prefix() throws IOException {
        Path sibling = Files.createDirectories(sentinel.resolveSibling("dc-other/task"));
        Files.writeString(sibling.resolve("file"), "keep");

        assertThatThrownBy(() -> deleter().deleteDir(sibling.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside of");

        assertThat(sibling.resolve("file")).hasContent("keep");
    }

    @Test
    public void refuses_dot_dot_leading_outside() throws IOException {
        Files.createDirectory(sentinel.resolve("a"));
        File escaping = new File(sentinel.toFile(), "a/../../outside");

        assertThatThrownBy(() -> deleter().deleteDir(escaping))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside of");

        assertThat(outsideMarker).hasContent("keep");
    }

    @Test
    public void resolves_dot_dot_after_a_link_physically() throws IOException {
        // sentinel/link -> sentinel/real/deep, so sentinel/link/../task is sentinel/real/task, not sentinel/task
        Path deep = Files.createDirectories(sentinel.resolve("real/deep"));
        Files.createSymbolicLink(sentinel.resolve("link"), deep);
        Path physical = Files.createDirectory(sentinel.resolve("real/task"));
        Path lexical  = Files.createDirectory(sentinel.resolve("task"));

        deleter().deleteDir(new File(sentinel.toFile(), "link/../task"));

        assertThat(physical).doesNotExist();
        assertThat(lexical).exists();
    }

    @Test
    public void refuses_dot_and_dot_dot_as_the_last_component() throws IOException {
        Files.createDirectory(sentinel.resolve("a"));

        assertThatThrownBy(() -> deleter().deleteDir(new File(sentinel.toFile(), "a/..")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deleter().deleteDir(new File(sentinel.toFile(), "a/.")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sentinel.resolve("a")).exists();
    }

    @Test
    public void refuses_the_sentinel_itself() throws IOException {
        Files.writeString(sentinel.resolve("file"), "keep");

        assertThatThrownBy(() -> deleter().deleteDir(sentinel.toFile()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sentinel.resolve("file")).hasContent("keep");
    }

    @Test
    public void accepts_a_sentinel_given_through_a_link() throws IOException {
        // like /tmp -> /private/tmp on macOS: the sentinel is a link, the target a real path
        Path sentinelLink = Files.createSymbolicLink(tmp.getRoot().toPath().toRealPath().resolve("dc-link"), sentinel);
        Path task         = Files.createDirectory(sentinel.resolve("task"));

        deleter(sentinelLink.toFile()).deleteDir(task.toFile());
        assertThat(task).doesNotExist();

        Path other = Files.createDirectory(sentinel.resolve("other"));
        deleter().deleteDir(sentinelLink.resolve("other").toFile());
        assertThat(other).doesNotExist();
    }

    @Test
    public void accepts_relative_paths() throws IOException {
        Path cwd = Path.of("").toAbsolutePath();
        Path task = Files.createDirectory(sentinel.resolve("task"));

        deleter(cwd.relativize(sentinel).toFile()).deleteDir(cwd.relativize(task).toFile());

        assertThat(task).doesNotExist();
    }

    @Test
    public void the_public_constructor_uses_secure_directory_stream_on_linux() {
        assumeTrue(isLinux());

        assertThat(new DeleteDirRecursively(sentinel.toFile()).usesSecureDirectoryStream()).isTrue();
        assertThat(deleter().usesSecureDirectoryStream()).isEqualTo(secure);
    }

    @Test
    public void a_directory_swapped_for_a_link_before_opening_is_not_entered() throws IOException {
        assumeTrue(isLinux() && secure);
        Path task = Files.createDirectory(sentinel.resolve("task"));
        Path x    = Files.createDirectory(task.resolve("x"));
        Files.createSymbolicLink(outside.resolve("self"), outsideMarker); // outside has more than the marker

        AtomicBoolean swapped = new AtomicBoolean();
        DeleteDirRecursively deleter = new DeleteDirRecursively(sentinel.toFile(), new NoHooks() {
            @Override
            public void beforeOpen(Path aDir) throws IOException {
                if (aDir.equals(x) && swapped.compareAndSet(false, true)) {
                    Files.delete(x);
                    Files.createSymbolicLink(x, outside);
                }
            }
        }, true);

        assertThatThrownBy(() -> deleter.deleteDir(task.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot delete " + x + " ");

        assertThat(swapped).isTrue();
        assertThat(outsideMarker).hasContent("keep");
        assertThat(Files.exists(outside.resolve("self"), LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    public void a_directory_swapped_after_opening_is_deleted_through_its_descriptor() throws IOException {
        assumeTrue(isLinux() && secure);
        Path task  = Files.createDirectory(sentinel.resolve("task"));
        Path x     = Files.createDirectory(task.resolve("x"));
        Path inner = Files.writeString(x.resolve("inner"), "delete me");
        Path moved = task.resolve("x-moved");

        AtomicBoolean swapped = new AtomicBoolean();
        DeleteDirRecursively deleter = new DeleteDirRecursively(sentinel.toFile(), new NoHooks() {
            @Override
            public void afterOpen(Path aDir) throws IOException {
                if (aDir.equals(x) && swapped.compareAndSet(false, true)) {
                    Files.move(x, moved);
                    Files.createSymbolicLink(x, outside);
                }
            }
        }, true);

        try {
            deleter.deleteDir(task.toFile());
        } catch (IllegalStateException e) {
            // deleteDirectory("x") may fail: x is a link now
        }

        assertThat(swapped).isTrue();
        assertThat(moved.resolve(inner.getFileName())).doesNotExist();
        assertThat(outsideMarker).hasContent("keep");
    }

    private DeleteDirRecursively deleter() {
        return deleter(sentinel.toFile());
    }

    private DeleteDirRecursively deleter(File aSentinel) {
        return new DeleteDirRecursively(aSentinel, new NoHooks(), secure);
    }

    private static class NoHooks implements DeleteDirRecursively.Hooks {

        @Override
        public void beforeOpen(Path aDir) throws IOException {
            // overridden by race tests
        }

        @Override
        public void afterOpen(Path aDir) throws IOException {
            // overridden by race tests
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }
}
