package com.payneteasy.dcagent.core.modules.docker.preflight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Deletes a test directory tree, including directories the test made read-only. */
public final class TempDirs {

    private TempDirs() {
    }

    public static void delete(Path aRoot) throws IOException {
        if (aRoot == null || !Files.exists(aRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(aRoot)) {
            paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            }
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
