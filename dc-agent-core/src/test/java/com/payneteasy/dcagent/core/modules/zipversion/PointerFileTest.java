package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PointerFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path                dir;
    private Path                pointer;
    private Path                previous;
    private RecordingDurability durability;
    private PointerFile         pointerFile;

    @Before
    public void setUp() throws IOException {
        dir         = tmp.newFolder("bundles").toPath().toRealPath();
        pointer     = dir.resolve("current");
        previous    = dir.resolve(".current.previous");
        durability  = new RecordingDurability();
        pointerFile = new PointerFile(dir, "current", durability);
    }

    // ── Format ───────────────────────────────────────────────────────────

    @Test
    public void reads_exactly_one_name_and_a_newline() throws IOException {
        assertThat(pointerFile.read()).isNull();
        for (String bad : new String[]{"v1", "v1\n\n", "v1\r\n", " v1\n", "v1 \n", "\n", "", ".v1\n", "a/b\n", "x".repeat(65) + "\n"}) {
            Files.write(pointer, bad.getBytes(US_ASCII));
            assertThat(pointerFile.read()).as(bad).isNull();
        }
        Files.writeString(pointer, "v0.4.1\n");
        assertThat(pointerFile.read()).isEqualTo("v0.4.1");
    }

    @Test
    public void a_pointer_that_is_a_link_counts_as_absent_and_is_replaced_not_followed() throws IOException {
        Path elsewhere = Files.writeString(tmp.newFile().toPath(), "v9\n");
        Files.createSymbolicLink(pointer, elsewhere);

        assertThat(pointerFile.read()).isNull();
        pointerFile.switchTo("v1");

        assertThat(Files.isSymbolicLink(pointer)).isFalse();
        assertThat(pointer).hasContent("v1\n");
        assertThat(elsewhere).hasContent("v9\n");
        assertThat(previous).hasContent("");
    }

    // ── Switch ───────────────────────────────────────────────────────────

    @Test
    public void the_first_switch_records_an_empty_previous_before_the_pointer() throws IOException {
        assertThat(pointerFile.switchTo("v1")).isEqualTo(PointerFile.Switch.SWITCHED);

        assertThat(pointer).hasContent("v1\n");
        assertThat(Files.size(previous)).isZero();
        assertThat(durability.marks()).containsExactly("previous-file", "previous-moved", "pointer-file", "pointer-moved");
        assertThat(listing()).containsExactly(".current.previous", "current");
    }

    @Test
    public void a_switch_records_the_old_value() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");

        assertThat(pointer).hasContent("v2\n");
        assertThat(previous).hasContent("v1\n");
    }

    @Test
    public void the_same_version_again_writes_nothing_but_syncs_dir() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");
        long previousModified = Files.getLastModifiedTime(previous).toMillis();
        durability.clear();

        assertThat(pointerFile.switchTo("v2")).isEqualTo(PointerFile.Switch.ALREADY);

        // .previous is not overwritten with the version itself
        assertThat(previous).hasContent("v1\n");
        assertThat(Files.getLastModifiedTime(previous).toMillis()).isEqualTo(previousModified);
        assertThat(durability.marks()).containsExactly("pointer-unchanged");
    }

    @Test
    public void a_failed_sync_after_previous_leaves_the_pointer_unchanged() throws IOException {
        pointerFile.switchTo("v1");
        durability.failOn("previous-moved", 1);

        assertThatThrownBy(() -> pointerFile.switchTo("v2")).isInstanceOf(DurabilityException.class);

        assertThat(pointer).hasContent("v1\n");
    }

    @Test
    public void a_failed_sync_after_the_pointer_move_is_a_500_and_the_retry_syncs_dir() throws IOException {
        durability.failOn("pointer-moved", 1);

        assertThatThrownBy(() -> pointerFile.switchTo("v1"))
                .isInstanceOfSatisfying(DurabilityException.class, e -> assertThat(e.getHttpCode()).isEqualTo(500));
        assertThat(pointer).hasContent("v1\n");

        durability.clear();
        assertThat(pointerFile.switchTo("v1")).isEqualTo(PointerFile.Switch.ALREADY);
        assertThat(durability.marks()).containsExactly("pointer-unchanged");
    }

    // ── Rollback ─────────────────────────────────────────────────────────

    @Test
    public void rollback_restores_the_previous_value() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");

        PointerFile.Rollback rollback = pointerFile.rollback("v2");

        assertThat(rollback.kind()).isEqualTo(PointerFile.RollbackKind.RESTORED);
        assertThat(rollback.value()).isEqualTo("v1");
        assertThat(pointer).hasContent("v1\n");
    }

    @Test
    public void rollback_of_the_first_publication_removes_the_pointer() throws IOException {
        pointerFile.switchTo("v1");

        assertThat(pointerFile.rollback("v1").kind()).isEqualTo(PointerFile.RollbackKind.REMOVED);
        assertThat(Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(durability.marks()).endsWith("rollback-removed");
    }

    @Test
    public void a_pointer_changed_by_hand_is_not_touched() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");
        Files.writeString(pointer, "v3\n");

        PointerFile.Rollback rollback = pointerFile.rollback("v2");

        assertThat(rollback.kind()).isEqualTo(PointerFile.RollbackKind.CHANGED_BY_HAND);
        assertThat(pointer).hasContent("v3\n");
    }

    @Test
    public void without_previous_the_pointer_is_left_as_it_is() throws IOException {
        Files.writeString(pointer, "v1\n");

        assertThat(pointerFile.rollback("v1").kind()).isEqualTo(PointerFile.RollbackKind.NO_PREVIOUS);
        assertThat(pointer).hasContent("v1\n");
    }

    @Test
    public void an_unreadable_previous_is_a_500_and_the_pointer_stays() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");
        Files.writeString(previous, "garbage \r\n");

        assertThatThrownBy(() -> pointerFile.rollback("v2"))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.getHttpCode()).isEqualTo(500);
                    assertThat(e.getMessage()).contains("rollback impossible");
                });
        assertThat(pointer).hasContent("v2\n");
    }

    @Test
    public void a_failed_sync_of_the_rollback_is_a_500_with_the_rollback_on_disk() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");
        durability.failOn("rollback-moved", 1);

        assertThatThrownBy(() -> pointerFile.rollback("v2")).isInstanceOf(DurabilityException.class);
        assertThat(pointer).hasContent("v1\n");
    }

    @Test
    public void the_agent_dies_between_switch_and_answer_and_the_retry_rolls_back_to_the_real_previous() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.switchTo("v2");
        // the agent is killed here; a new process retries the same version
        PointerFile restarted = new PointerFile(dir, "current", Durability.FS);

        assertThat(restarted.switchTo("v2")).isEqualTo(PointerFile.Switch.ALREADY);
        assertThat(restarted.rollback("v2").value()).isEqualTo("v1");
        assertThat(pointer).hasContent("v1\n");
    }

    @Test
    public void the_agent_dies_during_the_first_publication_and_the_retry_removes_the_pointer() throws IOException {
        pointerFile.switchTo("v1");
        PointerFile restarted = new PointerFile(dir, "current", Durability.FS);

        restarted.switchTo("v1");
        assertThat(restarted.rollback("v1").kind()).isEqualTo(PointerFile.RollbackKind.REMOVED);
        assertThatThrownBy(() -> Files.readString(pointer)).isInstanceOf(NoSuchFileException.class);
    }

    /**
     * Question A of the plan, decided by the user: a confirmed switch removes .previous, so a later
     * retry of the confirmed v2 that the service refuses leaves the pointer at v2 instead of rolling
     * back to v1.
     */
    @Test
    public void a_retry_of_a_confirmed_version_does_not_roll_back_past_it() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.settle();
        pointerFile.switchTo("v2");
        durability.clear();
        pointerFile.settle();
        assertThat(Files.exists(previous, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(durability.marks()).containsExactly("previous-cleared");

        assertThat(pointerFile.switchTo("v2")).isEqualTo(PointerFile.Switch.ALREADY);
        PointerFile.Rollback rollback = pointerFile.rollback("v2");

        assertThat(rollback.kind()).isEqualTo(PointerFile.RollbackKind.NO_PREVIOUS);
        assertThat(pointer).hasContent("v2\n");
    }

    @Test
    public void the_next_switch_after_a_confirmed_one_records_previous_again() throws IOException {
        pointerFile.switchTo("v1");
        pointerFile.settle();
        pointerFile.switchTo("v2");

        assertThat(previous).hasContent("v1\n");
        assertThat(pointerFile.rollback("v2").value()).isEqualTo("v1");
    }

    @Test
    public void settle_without_previous_does_nothing() throws IOException {
        Files.writeString(pointer, "v1\n");

        pointerFile.settle();

        assertThat(durability.marks()).isEmpty();
        assertThat(pointer).hasContent("v1\n");
    }

    // ── A reader never sees an empty or partial pointer ──────────────────

    @Test
    public void a_concurrent_reader_never_sees_an_empty_or_partial_pointer() throws Exception {
        pointerFile.switchTo("v-initial");
        AtomicBoolean           stop    = new AtomicBoolean();
        AtomicReference<String> problem = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    String text = Files.readString(pointer, US_ASCII);
                    if (!text.matches("v-[a-z0-9]+\n")) {
                        problem.set("read " + text.replace("\n", "\\n"));
                    }
                } catch (IOException e) {
                    problem.set("cannot read: " + e);
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 300; i++) {
                pointerFile.switchTo("v-" + i);
            }
        } finally {
            stop.set(true);
            reader.join();
        }
        assertThat(problem.get()).isNull();
    }

    private List<String> listing() throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }
}
