package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class VersionPublisherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path                dir;
    private RecordingDurability durability;
    private VersionPublisher    publisher;

    @Before
    public void setUp() throws IOException {
        dir        = tmp.newFolder("bundles").toPath().toRealPath();
        durability = new RecordingDurability();
        publisher  = new VersionPublisher(durability);
    }

    private static Map<String, String> files(String... aPathsAndContents) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < aPathsAndContents.length; i += 2) {
            files.put(aPathsAndContents[i], aPathsAndContents[i + 1]);
        }
        return files;
    }

    private VersionArchive archive(Map<String, String> aFiles) throws IOException {
        return ZipFixtures.archive(ZipFixtures.zip(tmp.newFile().toPath(), aFiles));
    }

    private VersionPublisher.Result publish(String aVersion, Map<String, String> aFiles) throws IOException {
        try (VersionArchive archive = archive(aFiles)) {
            return publisher.publish(dir, aVersion, archive);
        }
    }

    private List<String> listing() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.map(path -> dir.relativize(path).toString()).sorted().collect(Collectors.toList());
        }
    }

    // ── Publish ──────────────────────────────────────────────────────────

    @Test
    public void publishes_the_tree_and_its_digest() throws IOException {
        VersionPublisher.Result result;
        String digest;
        try (VersionArchive archive = archive(files("index.html", "<h1>", "mail/welcome.txt", "hi", "mail/deep/x.txt", "x"))) {
            digest = archive.digest();
            result = publisher.publish(dir, "v1", archive);
        }

        assertThat(result).isEqualTo(VersionPublisher.Result.PUBLISHED);
        assertThat(dir.resolve("v1/index.html")).hasContent("<h1>");
        assertThat(dir.resolve("v1/mail/welcome.txt")).hasContent("hi");
        assertThat(dir.resolve("v1/mail/deep/x.txt")).hasContent("x");
        assertThat(dir.resolve(".v1.sha256")).hasContent(digest + "\n");
        // no staging or temporary entries left
        assertThat(listing()).containsExactly("", ".v1.sha256", "v1", "v1/index.html", "v1/mail", "v1/mail/deep",
                "v1/mail/deep/x.txt", "v1/mail/welcome.txt");
    }

    @Test
    public void modes_are_set_explicitly_not_left_to_the_umask() throws IOException {
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        publish("v1", files("a/b.txt", "x"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("v1")))).isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("v1/a")))).isEqualTo("rwxr-xr-x");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("v1/a/b.txt")))).isEqualTo("rw-r--r--");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(".v1.sha256")))).isEqualTo("rw-r--r--");
    }

    @Test
    public void barriers_come_in_order_digest_before_version() throws IOException {
        publish("v1", files("a.txt", "1", "d/b.txt", "2"));

        List<String> marks = durability.marks();
        assertThat(marks).containsSubsequence("staging-file", "staging-file", "staging-dir", "digest-file", "digest-moved", "version-moved");
        assertThat(marks.lastIndexOf("staging-dir")).isLessThan(marks.indexOf("digest-file"));
        assertThat(marks.get(marks.size() - 1)).isEqualTo("version-moved");
    }

    // ── Existing version ─────────────────────────────────────────────────

    @Test
    public void the_same_contents_again_are_present_and_nothing_is_written() throws IOException {
        publish("v1", files("a.txt", "1", "b/c.txt", "2"));
        long modified = Files.getLastModifiedTime(dir.resolve("v1/a.txt")).toMillis();
        List<String> before = listing();
        durability.clear();

        // rebuilt archive: other order
        VersionPublisher.Result result = publish("v1", files("b/c.txt", "2", "a.txt", "1"));

        assertThat(result).isEqualTo(VersionPublisher.Result.PRESENT);
        assertThat(listing()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(dir.resolve("v1/a.txt")).toMillis()).isEqualTo(modified);
        // an earlier call may have failed exactly at the last fsync
        assertThat(durability.marks()).containsExactly("present");
    }

    @Test
    public void other_contents_are_a_409_and_create_nothing() throws IOException {
        publish("v1", files("a.txt", "1"));
        List<String> before = listing();

        assertThatThrownBy(() -> publish("v1", files("a.txt", "2")))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.getHttpCode()).isEqualTo(409));
        assertThat(listing()).isEqualTo(before);
        assertThat(dir.resolve("v1/a.txt")).hasContent("1");
    }

    @Test
    public void a_version_without_a_digest_is_a_409() throws IOException {
        Files.createDirectories(dir.resolve("v1"));
        Files.writeString(dir.resolve("v1/a.txt"), "by hand");

        assertConflict("v1", files("a.txt", "by hand"));
        assertThat(dir.resolve("v1/a.txt")).hasContent("by hand");
    }

    @Test
    public void a_digest_file_that_is_a_link_counts_as_missing() throws IOException {
        String digest;
        try (VersionArchive archive = archive(files("a.txt", "1"))) {
            digest = archive.digest();
        }
        Files.createDirectories(dir.resolve("v1"));
        Path real = Files.writeString(tmp.newFile().toPath(), digest + "\n");
        Files.createSymbolicLink(dir.resolve(".v1.sha256"), real);

        assertConflict("v1", files("a.txt", "1"));
    }

    @Test
    public void a_version_that_is_a_link_or_a_file_is_a_409_and_is_not_followed() throws IOException {
        Path outside = tmp.newFolder("outside").toPath();
        Files.createSymbolicLink(dir.resolve("v1"), outside);
        Files.writeString(dir.resolve("v2"), "a file");

        assertConflict("v1", files("a.txt", "1"));
        assertConflict("v2", files("a.txt", "1"));
        try (Stream<Path> list = Files.list(outside)) {
            assertThat(list).isEmpty();
        }
    }

    private void assertConflict(String aVersion, Map<String, String> aFiles) throws IOException {
        List<String> before = listing();
        assertThatThrownBy(() -> publish(aVersion, aFiles))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.getHttpCode()).isEqualTo(409));
        assertThat(listing()).isEqualTo(before);
    }

    // ── Leftovers of a crash ─────────────────────────────────────────────

    @Test
    public void leftover_staging_entries_are_removed_without_following_links() throws IOException {
        Path outside = tmp.newFolder("outside").toPath();
        Files.writeString(outside.resolve("precious.txt"), "keep");
        Files.createSymbolicLink(dir.resolve(".v1.new-00000000000000aa"), outside);
        Path realLeftover = Files.createDirectories(dir.resolve(".v1.new-00000000000000bb/sub"));
        Files.createSymbolicLink(realLeftover.resolve("link"), outside);
        Files.writeString(realLeftover.resolve("half.txt"), "half");
        Files.writeString(dir.resolve(".v1.sha256"), "0".repeat(64) + "\n");

        assertThat(publish("v1", files("a.txt", "1"))).isEqualTo(VersionPublisher.Result.PUBLISHED);

        assertThat(Files.exists(dir.resolve(".v1.new-00000000000000aa"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(dir.resolve(".v1.new-00000000000000bb"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(outside.resolve("precious.txt")).hasContent("keep");
        // a digest without a version is simply overwritten
        assertThat(Files.readString(dir.resolve(".v1.sha256"))).isNotEqualTo("0".repeat(64) + "\n");
        assertThat(dir.resolve("v1/a.txt")).hasContent("1");
    }

    @Test
    public void the_cleanup_of_one_version_never_takes_the_files_of_another() throws IOException {
        // v1.new-release is a valid version; its digest starts with ".v1.new-"
        publish("v1.new-release", files("a.txt", "release"));
        publish("v1.new-0123456789abcdef", files("a.txt", "lookalike"));
        List<String> before = listing();

        assertThat(publish("v1", files("a.txt", "1"))).isEqualTo(VersionPublisher.Result.PUBLISHED);

        assertThat(listing()).containsAll(before);
        assertThat(publish("v1.new-release", files("a.txt", "release"))).isEqualTo(VersionPublisher.Result.PRESENT);
        assertThat(publish("v1.new-0123456789abcdef", files("a.txt", "lookalike"))).isEqualTo(VersionPublisher.Result.PRESENT);
    }

    // ── Pass 2 must match pass 1 ─────────────────────────────────────────

    @Test
    public void staged_bytes_that_differ_from_pass_1_publish_nothing() throws IOException {
        Path zip = ZipFixtures.zip(tmp.newFile().toPath(), files("a.txt", "AAAA", "b.txt", "BBBB"), true);
        try (VersionArchive archive = ZipFixtures.archive(zip)) {
            // the upload changes on disk between the passes (same size, stored: no decompression error)
            byte[] bytes = Files.readAllBytes(zip);
            int    at    = new String(bytes, UTF_8).indexOf("BBBB");
            try (RandomAccessFile file = new RandomAccessFile(zip.toFile(), "rw")) {
                file.seek(at);
                file.write("XXXX".getBytes(UTF_8));
            }

            assertThatThrownBy(() -> publisher.publish(dir, "v1", archive))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("differ");
        }
        assertThat(listing()).containsExactly("");
    }

    // ── fsync failures ───────────────────────────────────────────────────

    @Test
    public void a_failed_sync_after_the_digest_move_publishes_no_version() throws IOException {
        durability.failOn("digest-moved", 1);

        assertThatThrownBy(() -> publish("v1", files("a.txt", "1")))
                .isInstanceOfSatisfying(DurabilityException.class, e -> assertThat(e.getHttpCode()).isEqualTo(500));
        assertThat(Files.exists(dir.resolve("v1"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(listing()).containsExactly("", ".v1.sha256");

        assertThat(publish("v1", files("a.txt", "1"))).isEqualTo(VersionPublisher.Result.PUBLISHED);
    }

    @Test
    public void a_failed_sync_of_a_staged_file_publishes_nothing() throws IOException {
        durability.failOn("staging-file", 1);

        assertThatThrownBy(() -> publish("v1", files("a.txt", "1"))).isInstanceOf(DurabilityException.class);
        assertThat(listing()).containsExactly("");
    }

    @Test
    public void a_failed_sync_after_the_version_move_is_a_500_and_a_retry_settles_it() throws IOException {
        durability.failOn("version-moved", 1);

        assertThatThrownBy(() -> publish("v1", files("a.txt", "1")))
                .isInstanceOfSatisfying(DurabilityException.class, e -> {
                    assertThat(e.getHttpCode()).isEqualTo(500);
                    assertThat(e.getMessage()).startsWith("durability not confirmed");
                });
        // the intended state is on disk
        assertThat(dir.resolve("v1/a.txt")).hasContent("1");

        durability.clear();
        assertThat(publish("v1", files("a.txt", "1"))).isEqualTo(VersionPublisher.Result.PRESENT);
        assertThat(durability.marks()).containsExactly("present");
    }

    // ── dir itself ───────────────────────────────────────────────────────

    @Test
    public void prepare_dir_creates_only_dir_and_syncs_its_parent_every_time() throws IOException {
        Path parent = tmp.newFolder("opt").toPath().toRealPath();
        Path target = parent.resolve("bundles");

        assertThat(publisher.prepareDir(target)).isEqualTo(target);
        assertThat(target).isDirectory();
        assertThat(durability.marks()).containsExactly("dir-parent");

        durability.failOn("dir-parent", 1);
        assertThatThrownBy(() -> publisher.prepareDir(target)).isInstanceOf(DurabilityException.class);
        // the retry finds dir already there and still syncs the parent
        durability.clear();
        publisher.prepareDir(target);
        assertThat(durability.marks()).containsExactly("dir-parent");

        assertThatThrownBy(() -> publisher.prepareDir(parent.resolve("missing/bundles"))).isInstanceOf(IOException.class);
        assertThat(parent.resolve("missing")).doesNotExist();
    }

    // ── A reader never sees a partial version ────────────────────────────

    @Test
    public void a_concurrent_reader_never_sees_a_partial_version() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            files.put("f/" + i + ".txt", "content-" + i);
        }
        AtomicBoolean           stop    = new AtomicBoolean();
        AtomicReference<String> problem = new AtomicReference<>();
        List<String>            seen    = new ArrayList<>();
        Thread reader = new Thread(() -> {
            // the last pass starts after the writer is done, so the reader sees every version at least once
            for (boolean last = false; !last; ) {
                last = stop.get();
                for (int v = 0; v < 5; v++) {
                    Path version = dir.resolve("v" + v);
                    if (Files.isDirectory(version)) {
                        for (Map.Entry<String, String> file : files.entrySet()) {
                            try {
                                String content = Files.readString(version.resolve(file.getKey()));
                                if (!content.equals(file.getValue())) {
                                    problem.set("partial " + file.getKey() + " in v" + v);
                                }
                            } catch (IOException e) {
                                problem.set("missing " + file.getKey() + " in v" + v);
                            }
                        }
                        synchronized (seen) {
                            seen.add("v" + v);
                        }
                    }
                }
            }
        });
        reader.start();
        try {
            for (int v = 0; v < 5; v++) {
                publish("v" + v, files);
            }
        } finally {
            stop.set(true);
            reader.join();
        }
        assertThat(problem.get()).isNull();
        assertThat(seen).contains("v0", "v1", "v2", "v3", "v4");
    }
}
