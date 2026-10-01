package com.payneteasy.dcagent.core.modules.zipversion;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

public class VersionArchiveTest {

    private static final long MAX_BYTES   = 1024 * 1024;
    private static final int  MAX_ENTRIES = 100;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ── Accepted ─────────────────────────────────────────────────────────

    @Test
    public void reads_files_and_skips_directory_entries() throws IOException {
        try (VersionArchive archive = read(zip(e("./"), e("a/"), e("a/b.txt", "bee"), e("top.txt", "top"), e("empty.txt", "")))) {
            assertThat(paths(archive)).containsExactlyInAnyOrder("a/b.txt", "top.txt", "empty.txt");
            assertThat(archive.totalBytes()).isEqualTo(6);
            VersionArchive.FileEntry bee = archive.files().stream().filter(f -> f.path().equals("a/b.txt")).findFirst().orElseThrow();
            assertThat(bee.size()).isEqualTo(3);
            assertThat(bee.sha256()).isEqualTo(sha256Hex("bee".getBytes(UTF_8)));
            try (InputStream in = archive.open(bee)) {
                assertThat(in.readAllBytes()).isEqualTo("bee".getBytes(UTF_8));
            }
        }
    }

    @Test
    public void the_digest_has_the_documented_format() throws IOException {
        // lines "<path>\0<sha256 hex>\n" sorted by the UTF-8 bytes of the path: "B" (0x42) < "a" (0x61) < "é" (0xC3 0xA9)
        String expected = sha256Hex(("B\0" + sha256Hex("2".getBytes(UTF_8)) + "\n"
                + "a/x\0" + sha256Hex("1".getBytes(UTF_8)) + "\n"
                + "é\0" + sha256Hex("3".getBytes(UTF_8)) + "\n").getBytes(UTF_8));

        try (VersionArchive archive = read(zip(e("é", "3"), e("a/x", "1"), e("B", "2")))) {
            assertThat(archive.digest()).isEqualTo(expected);
        }
    }

    @Test
    public void the_digest_sorts_by_utf8_bytes_not_by_utf16() throws IOException {
        // U+E000 is EE 80 80 and U+10000 is F0 90 80 80 in UTF-8, so E000 sorts first;
        // String.compareTo sees the surrogate D800 for U+10000 and would sort it first
        String bmp   = "\uE000";
        String astra = new String(Character.toChars(0x10000));
        String expected = sha256Hex((bmp + "\0" + sha256Hex("1".getBytes(UTF_8)) + "\n"
                + astra + "\0" + sha256Hex("2".getBytes(UTF_8)) + "\n").getBytes(UTF_8));
        assertThat(astra.compareTo(bmp)).isNegative();

        try (VersionArchive archive = read(zip(e(astra, "2"), e(bmp, "1")))) {
            assertThat(archive.digest()).isEqualTo(expected);
        }
    }

    @Test
    public void the_same_files_rezipped_give_the_same_digest() throws IOException {
        String first;
        try (VersionArchive archive = read(zip(e("a/b.txt", "bee"), e("top.txt", "top")))) {
            first = archive.digest();
        }
        // other order, other timestamps, a leading ./, extra directory entries, stored instead of deflated
        Path rebuilt = tmp.newFile().toPath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(rebuilt))) {
            out.setLevel(0);
            for (String dir : new String[]{"./", "a/", "./a/"}) {
                ZipEntry entry = new ZipEntry(dir);
                entry.setLastModifiedTime(FileTime.fromMillis(0));
                out.putNextEntry(entry);
                out.closeEntry();
            }
            for (String[] file : new String[][]{{"./top.txt", "top"}, {"a/b.txt", "bee"}}) {
                ZipEntry entry = new ZipEntry(file[0]);
                entry.setLastModifiedTime(FileTime.fromMillis(1_000_000_000_000L));
                out.putNextEntry(entry);
                out.write(file[1].getBytes(UTF_8));
                out.closeEntry();
            }
        }
        try (VersionArchive archive = read(rebuilt)) {
            assertThat(archive.digest()).isEqualTo(first);
        }
    }

    @Test
    public void other_contents_or_names_give_another_digest() throws IOException {
        String base    = digest(zip(e("a.txt", "one")));
        String content = digest(zip(e("a.txt", "two")));
        String name    = digest(zip(e("b.txt", "one")));
        String extra   = digest(zip(e("a.txt", "one"), e("b.txt", "")));

        assertThat(List.of(content, name, extra)).doesNotContain(base).doesNotHaveDuplicates();
    }

    @Test
    public void directory_entry_with_its_files_and_segment_prefixes_are_fine() throws IOException {
        try (VersionArchive archive = read(zip(e("a/"), e("a/b.txt", "x"), e("artifact", "1"), e("artifact2/x", "2"), e(".htaccess", "h")))) {
            assertThat(paths(archive)).containsExactlyInAnyOrder("a/b.txt", "artifact", "artifact2/x", ".htaccess");
        }
    }

    @Test
    public void unix_regular_and_directory_modes_are_fine() throws IOException {
        try (VersionArchive archive = read(commonsZip(unix("bin/", 040755, null), unix("bin/run.sh", 0100755, "#!/bin/sh")))) {
            assertThat(paths(archive)).containsExactly("bin/run.sh");
        }
    }

    // ── Refused ──────────────────────────────────────────────────────────

    @Test
    public void bad_names_are_refused() throws IOException {
        for (String bad : new String[]{"../x", "a/../b", "a/./b", "/etc/passwd", "a//b", "", "a\u0000b", "a\nb",
                "././a", "./../a"}) {
            assertThat(catchThrowable(() -> read(zipCommons(bad)))).as(bad).isInstanceOf(ArchiveRefusedException.class);
        }
        for (String badDir : new String[]{"../", "/", "a//", "./../"}) {
            assertThat(catchThrowable(() -> read(zipCommons(badDir, "ok.txt")))).as(badDir).isInstanceOf(ArchiveRefusedException.class);
        }
    }

    @Test
    public void a_backslash_is_refused_not_normalised() throws IOException {
        // java.util.zip stores the name bytes as given; commons-compress would turn '\\' into '/' on read
        for (String name : new String[]{"a\\b.txt", "dir\\", "a/b\\c"}) {
            Path zip = name.endsWith("\\") ? zip(e(name), e("ok.txt", "x")) : zip(e(name, "x"));
            assertThat(new String(Files.readAllBytes(zip), UTF_8)).contains(name);
            assertThat(catchThrowable(() -> read(zip))).as(name)
                    .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("backslash");
        }
    }

    @Test
    public void a_unicode_path_extra_field_does_not_replace_the_checked_name() throws IOException {
        Path zip = tmp.newFile().toPath();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            // no UTF-8 (EFS) flag: only then does a reader take the name from the extra field
            out.setUseLanguageEncodingFlag(false);
            ZipArchiveEntry entry = new ZipArchiveEntry("safe.txt");
            entry.addExtraField(new UnicodePathExtraField("a\\b.txt", "safe.txt".getBytes(UTF_8)));
            out.putArchiveEntry(entry);
            out.write("x".getBytes(UTF_8));
            out.closeArchiveEntry();
        }

        try (VersionArchive archive = read(zip)) {
            assertThat(paths(archive)).containsExactly("safe.txt");
        }
    }

    @Test
    public void the_channel_is_closed_on_every_refusal_and_by_close() throws IOException {
        // a valid end record pointing at a broken central directory: parsing fails after the channel is open
        Path broken = zip(e("a.txt", "x"));
        byte[] bytes = Files.readAllBytes(broken);
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x01 && bytes[i + 3] == 0x02) {
                bytes[i + 3] = 0x7f;
            }
        }
        Files.write(broken, bytes);
        SeekableByteChannel parse = Files.newByteChannel(broken);
        assertThat(catchThrowable(() -> VersionArchive.read(parse, MAX_BYTES, MAX_ENTRIES))).isInstanceOf(ArchiveRefusedException.class);
        assertThat(parse.isOpen()).isFalse();

        SeekableByteChannel rule = Files.newByteChannel(zip(e("../x", "x")));
        assertThat(catchThrowable(() -> VersionArchive.read(rule, MAX_BYTES, MAX_ENTRIES))).isInstanceOf(ArchiveRefusedException.class);
        assertThat(rule.isOpen()).isFalse();

        SeekableByteChannel good = Files.newByteChannel(zip(e("a.txt", "x")));
        VersionArchive archive = VersionArchive.read(good, MAX_BYTES, MAX_ENTRIES);
        assertThat(good.isOpen()).isTrue();
        archive.close();
        assertThat(good.isOpen()).isFalse();
    }

    @Test
    public void the_refusal_is_a_400() {
        assertThatThrownBy(() -> read(zipCommons("../x")))
                .isInstanceOfSatisfying(ArchiveRefusedException.class, e -> {
                    assertThat(e.getHttpCode()).isEqualTo(400);
                    assertThat(e.getMessage()).startsWith("archive refused: ");
                });
    }

    @Test
    public void duplicate_normalised_names_are_refused() {
        assertThatThrownBy(() -> read(commonsZip(unix("a.txt", 0, "1"), unix("./a.txt", 0, "2"))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("two entries");
        assertThatThrownBy(() -> read(commonsZip(unix("a.txt", 0, "1"), unix("a.txt", 0, "2"))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("two entries");
    }

    @Test
    public void a_file_that_is_also_a_directory_is_refused() {
        assertThatThrownBy(() -> read(zip(e("a", "file"), e("a/b.txt", "x"))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("also a directory");
        assertThatThrownBy(() -> read(zip(e("a/b/c.txt", "x"), e("a", "file"))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("also a directory");
    }

    @Test
    public void a_symbolic_link_entry_is_refused() {
        assertThatThrownBy(() -> read(commonsZip(unix("ok.txt", 0, "1"), unix("link", 0120777, "/etc/shadow"))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("symbolic link");
    }

    @Test
    public void other_unix_types_and_mismatched_types_are_refused() {
        assertThatThrownBy(() -> read(commonsZip(unix("fifo", 010644, ""))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("not a regular file");
        assertThatThrownBy(() -> read(commonsZip(unix("dir-without-slash", 040755, ""))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("does not match");
    }

    @Test
    public void no_file_entries_is_refused() {
        assertThatThrownBy(() -> read(zip(e("./"), e("a/"), e("a/b/"))))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("no file entries");
    }

    @Test
    public void max_entries_counts_directory_entries() throws IOException {
        Path zip = zip(e("d/"), e("d/a", "1"), e("b", "2"));

        try (VersionArchive archive = VersionArchive.read(zip, MAX_BYTES, 3)) {
            assertThat(archive.files()).hasSize(2);
        }
        assertThatThrownBy(() -> VersionArchive.read(zip, MAX_BYTES, 2))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("maxEntries");
    }

    @Test
    public void max_bytes_counts_the_bytes_read_not_the_declared_size() throws IOException {
        byte[] big = "a".repeat(5000).getBytes(UTF_8);
        Path   zip = zip(e("big.txt", big));
        declareUncompressedSize(zip, 10);

        assertThatThrownBy(() -> VersionArchive.read(zip, 1000, MAX_ENTRIES))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("maxBytes");
        assertThatThrownBy(() -> VersionArchive.read(zip(e("a", "123"), e("b", "456")), 5, MAX_ENTRIES))
                .isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("maxBytes");
        try (VersionArchive archive = VersionArchive.read(zip(e("a", "123"), e("b", "456")), 6, MAX_ENTRIES)) {
            assertThat(archive.totalBytes()).isEqualTo(6);
        }
    }

    @Test
    public void not_a_zip_is_refused() throws IOException {
        Path junk = tmp.newFile().toPath();
        Files.writeString(junk, "this is not a zip");

        assertThatThrownBy(() -> read(junk)).isInstanceOf(ArchiveRefusedException.class).hasMessageContaining("not a readable ZIP");
    }

    @Test
    public void a_refused_name_is_sanitised_in_the_reason() {
        assertThatThrownBy(() -> read(zipCommons("../<script>\u0007")))
                .hasMessageNotContaining("<script>")
                .hasMessageNotContaining("\u0007");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private record E(String name, byte[] data) {
    }

    private static E e(String aDir) {
        return new E(aDir, null);
    }

    private static E e(String aName, String aData) {
        return new E(aName, aData.getBytes(UTF_8));
    }

    private static E e(String aName, byte[] aData) {
        return new E(aName, aData);
    }

    private record U(String name, int mode, String data) {
    }

    private static U unix(String aName, int aMode, String aData) {
        return new U(aName, aMode, aData);
    }

    private VersionArchive read(Path aZip) throws IOException {
        return VersionArchive.read(aZip, MAX_BYTES, MAX_ENTRIES);
    }

    private String digest(Path aZip) throws IOException {
        try (VersionArchive archive = read(aZip)) {
            return archive.digest();
        }
    }

    private static List<String> paths(VersionArchive aArchive) {
        return aArchive.files().stream().map(VersionArchive.FileEntry::path).collect(Collectors.toList());
    }

    /** java.util.zip, deflated: what most tools produce. */
    private Path zip(E... aEntries) throws IOException {
        Path zip = tmp.newFile().toPath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (E entry : aEntries) {
                out.putNextEntry(new ZipEntry(entry.name()));
                if (entry.data() != null) {
                    out.write(entry.data());
                }
                out.closeEntry();
            }
        }
        return zip;
    }

    /** commons-compress writer: Unix modes, duplicate and odd names that java.util.zip refuses to write. */
    private Path commonsZip(U... aEntries) throws IOException {
        Path zip = tmp.newFile().toPath();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            for (U entry : aEntries) {
                ZipArchiveEntry zipEntry = new ZipArchiveEntry(entry.name());
                if (entry.mode() != 0) {
                    zipEntry.setUnixMode(entry.mode());
                }
                out.putArchiveEntry(zipEntry);
                if (entry.data() != null) {
                    out.write(entry.data().getBytes(UTF_8));
                }
                out.closeArchiveEntry();
            }
        }
        return zip;
    }

    /** One entry per name; a name ending in / is a directory entry. */
    private Path zipCommons(String... aNames) throws IOException {
        List<U> entries = new ArrayList<>();
        for (String name : aNames) {
            entries.add(unix(name, 0, name.endsWith("/") ? null : "x"));
        }
        return commonsZip(entries.toArray(new U[0]));
    }

    /** Patches the uncompressed size in every central directory header — a ZIP that lies about its sizes. */
    private static void declareUncompressedSize(Path aZip, int aSize) throws IOException {
        byte[] bytes   = Files.readAllBytes(aZip);
        int    patched = 0;
        for (int i = 0; i + 28 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x01 && bytes[i + 3] == 0x02) {
                bytes[i + 24] = (byte) aSize;
                bytes[i + 25] = (byte) (aSize >> 8);
                bytes[i + 26] = 0;
                bytes[i + 27] = 0;
                patched++;
            }
        }
        assertThat(patched).isEqualTo(1);
        Files.write(aZip, bytes);
    }

    private static String sha256Hex(byte[] aBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(aBytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
