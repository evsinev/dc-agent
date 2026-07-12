package com.payneteasy.dcagent.core.modules.zipachive;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ZipFileExtractorTest {

    private final ZipFileExtractor extractor = new ZipFileExtractor();

    @Test
    public void keeps_stale_files_when_delete_is_false() throws IOException {
        Path target = newTargetDir();
        File zip    = newArchive();

        extractor.extractZip(zip, target.toFile());

        assertThat(read(target, "a.txt")).isEqualTo("new-a");
        assertThat(read(target, "sub/b.txt")).isEqualTo("new-b");
        assertThat(exists(target, "stale.txt")).isTrue();
        assertThat(exists(target, "sub/old.txt")).isTrue();
        assertThat(exists(target, "stale-dir/x.txt")).isTrue();
    }

    @Test
    public void mirrors_archive_when_delete_is_true() throws IOException {
        Path target = newTargetDir();
        File zip    = newArchive();

        extractor.extractZip(zip, target.toFile(), true);

        assertThat(read(target, "a.txt")).isEqualTo("new-a");
        assertThat(read(target, "sub/b.txt")).isEqualTo("new-b");
        assertThat(exists(target, "stale.txt")).isFalse();
        assertThat(exists(target, "sub/old.txt")).isFalse();
        assertThat(exists(target, "stale-dir")).isFalse();
        assertThat(exists(target, "sub")).isTrue();
    }

    private Path newTargetDir() throws IOException {
        Path dir = Files.createTempDirectory("zip-extractor-target");
        write(dir, "a.txt", "old-a");
        write(dir, "stale.txt", "stale");
        write(dir, "sub/b.txt", "old-b");
        write(dir, "sub/old.txt", "old");
        write(dir, "stale-dir/x.txt", "x");
        return dir;
    }

    private File newArchive() throws IOException {
        File zip = File.createTempFile("zip-extractor-archive", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            putEntry(out, "a.txt", "new-a");
            putEntry(out, "sub/b.txt", "new-b");
        }
        return zip;
    }

    private static void putEntry(ZipOutputStream aOut, String aName, String aContent) throws IOException {
        aOut.putNextEntry(new ZipEntry(aName));
        aOut.write(aContent.getBytes(UTF_8));
        aOut.closeEntry();
    }

    private static void write(Path aRoot, String aRelative, String aContent) throws IOException {
        Path file = aRoot.resolve(aRelative);
        Files.createDirectories(file.getParent());
        Files.write(file, aContent.getBytes(UTF_8));
    }

    private static String read(Path aRoot, String aRelative) throws IOException {
        return new String(Files.readAllBytes(aRoot.resolve(aRelative)), UTF_8);
    }

    private static boolean exists(Path aRoot, String aRelative) {
        return Files.exists(aRoot.resolve(aRelative));
    }
}
