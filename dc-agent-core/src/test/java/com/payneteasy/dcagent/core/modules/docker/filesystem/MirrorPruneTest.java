package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.modules.docker.resolver.KeepPatterns;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

/**
 * Races around {@link MirrorPrune}, for both implementations: descriptor-relative (Linux) and the
 * path walk. The whole behaviour is covered through PushDockerAction in PushDockerActionMirrorTest.
 */
@RunWith(Parameterized.class)
public class MirrorPruneTest {

    @Parameterized.Parameters(name = "secure={0}")
    public static List<Boolean> implementations() {
        return List.of(true, false);
    }

    @Parameterized.Parameter
    public boolean secure;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path base;
    private Path task;
    private Path root;
    private Path outsideMarker;

    @Before
    public void setUp() throws IOException {
        base = tmp.getRoot().toPath().toRealPath();
        task = Files.createDirectories(base.resolve("task/conf"));
        Files.writeString(task.resolve("main.conf"), "main");
        root = Files.createDirectories(base.resolve("app/conf"));
        Files.writeString(root.resolve("main.conf"), "main");
        Path outside = Files.createDirectories(base.resolve("outside"));
        outsideMarker = Files.writeString(outside.resolve("marker"), "keep");
    }

    @Test
    public void on_linux_the_secure_implementation_is_used() {
        assumeTrue("SecureDirectoryStream exists on Linux only", isLinux() && secure);
        try (MirrorPrune prune = MirrorPrune.open(target(), null, true)) {
            assertThat(prune.usesSecureDirectoryStream()).isTrue();
        }
    }

    @Test
    public void the_fallback_is_used_when_asked() {
        assumeTrue(!secure);
        try (MirrorPrune prune = MirrorPrune.open(target(), null, false)) {
            assertThat(prune.usesSecureDirectoryStream()).isFalse();
        }
    }

    @Test
    public void a_directory_swapped_for_a_link_while_walking_fails_and_the_outside_is_untouched() throws IOException {
        Files.createDirectories(root.resolve("old"));
        Files.writeString(root.resolve("old/x"), "x");

        MirrorPrune.Hooks swap = aRelative -> {
            if (aRelative.toString().equals("old")) {
                Files.move(root.resolve("old"), base.resolve("moved-old"));
                Files.createSymbolicLink(root.resolve("old"), outsideMarker.getParent());
            }
        };

        try (MirrorPrune prune = MirrorPrune.open(target(), swap, secure)) {
            assertThatThrownBy(prune::plan).hasMessageContaining("volumes[0].dirConfig (sync: MIRROR)");
        }
        assertThat(outsideMarker).hasContent("keep");
    }

    @Test
    public void a_directory_swapped_with_a_protected_one_while_walking_fails_and_the_protected_one_is_kept() throws IOException {
        Path runtime = Files.createDirectories(root.resolve("runtime"));
        Files.writeString(Files.createDirectories(runtime.resolve("old")).resolve("x"), "x");
        Path state = Files.createDirectories(runtime.resolve("state"));
        Files.writeString(state.resolve("data"), "data");
        Object stateKey = Files.readAttributes(state, java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        MirrorPrune.Hooks swap = aRelative -> {
            if (aRelative.toString().equals("runtime/old")) {
                Files.move(runtime.resolve("old"), runtime.resolve("tmp"));
                Files.move(runtime.resolve("state"), runtime.resolve("old"));
                Files.move(runtime.resolve("tmp"), runtime.resolve("state"));
            }
        };
        MirrorTarget target = new MirrorTarget("volumes[0].dirConfig", task, root, KeepPatterns.none(), Set.of(), Set.of(), Set.of(stateKey));

        try (MirrorPrune prune = MirrorPrune.open(target, swap, secure)) {
            assertThatThrownBy(prune::plan).hasMessageContaining("changed while walking");
        }
        assertThat(runtime.resolve("old/data")).hasContent("data");
    }

    @Test
    public void a_directory_swapped_with_a_protected_one_after_the_plan_is_not_entered() throws IOException {
        Path runtime = Files.createDirectories(root.resolve("runtime"));
        Path state = Files.createDirectories(runtime.resolve("state"));
        Files.writeString(state.resolve("data"), "data");
        Files.createDirectories(runtime.resolve("old"));
        // the same inode in both directories: the check of the file itself cannot tell them apart
        Files.createLink(runtime.resolve("old/data"), state.resolve("data"));
        Object stateKey = Files.readAttributes(state, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        MirrorTarget target = new MirrorTarget("volumes[0].dirConfig", task, root, KeepPatterns.none(), Set.of(), Set.of(), Set.of(stateKey));

        try (MirrorPrune prune = MirrorPrune.open(target, null, secure)) {
            assertThat(prune.plan()).extracting(MirrorPrune.Deletion::relative).contains(Path.of("runtime/old/data"));

            Files.move(runtime.resolve("old"), runtime.resolve("tmp"));
            Files.move(runtime.resolve("state"), runtime.resolve("old"));
            Files.move(runtime.resolve("tmp"), runtime.resolve("state"));

            assertThatThrownBy(() -> prune.execute(deletion -> { })).hasMessageContaining("is not the one the plan walked");
        }
        assertThat(runtime.resolve("old/data")).hasContent("data");
    }

    @Test
    public void a_component_of_the_root_swapped_for_a_link_before_opening_fails() throws IOException {
        MirrorTarget target = target();

        // the same tree elsewhere, reachable through a link where 'app' was
        Path other = Files.createDirectories(base.resolve("other/conf"));
        Path victim = Files.writeString(other.resolve("victim"), "victim");
        Files.move(base.resolve("app"), base.resolve("app-real"));
        Files.createSymbolicLink(base.resolve("app"), base.resolve("other"));

        assertThatThrownBy(() -> MirrorPrune.open(target, null, secure).close())
                .hasMessageContaining("without following symbolic links");
        assertThat(victim).hasContent("victim");
    }

    @Test
    public void an_entry_replaced_after_the_plan_is_not_deleted() throws IOException {
        Files.writeString(root.resolve("extra"), "old");

        try (MirrorPrune prune = MirrorPrune.open(target(), null, secure)) {
            assertThat(prune.plan()).extracting(MirrorPrune.Deletion::relative).containsExactly(Path.of("extra"));

            // a new object at the same name (both exist at once, so it is not the same inode)
            Files.writeString(root.resolve("extra.new"), "new");
            Files.move(root.resolve("extra.new"), root.resolve("extra"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            assertThatThrownBy(() -> prune.execute(deletion -> { })).hasMessageContaining("it is not the entry the plan saw");
        }
        assertThat(root.resolve("extra")).hasContent("new");
    }

    @Test
    public void a_planned_directory_that_got_a_new_entry_is_not_deleted() throws IOException {
        Files.createDirectories(root.resolve("old"));
        Files.writeString(root.resolve("old/x"), "x");

        List<Path> deleted = new ArrayList<>();
        try (MirrorPrune prune = MirrorPrune.open(target(), null, secure)) {
            prune.plan();
            Files.writeString(root.resolve("old/new"), "new");

            assertThatThrownBy(() -> prune.execute(deletion -> deleted.add(deletion.relative())))
                    .hasMessageContaining("cannot delete");
        }
        assertThat(deleted).containsExactly(Path.of("old/x"));
        assertThat(root.resolve("old/new")).hasContent("new");
    }

    @Test
    public void a_missing_root_plans_nothing() {
        MirrorTarget target = new MirrorTarget("volumes[0].dirConfig", task, base.resolve("missing"), KeepPatterns.none(), Set.of(), Set.of(), Set.of());
        try (MirrorPrune prune = MirrorPrune.open(target, null, secure)) {
            assertThat(prune.plan()).isEmpty();
        }
    }

    private MirrorTarget target() {
        return new MirrorTarget("volumes[0].dirConfig", task, root, KeepPatterns.none(), Set.of(), Set.of(), Set.of());
    }

    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }
}
