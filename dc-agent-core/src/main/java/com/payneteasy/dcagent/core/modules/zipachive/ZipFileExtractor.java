package com.payneteasy.dcagent.core.modules.zipachive;

import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.util.Streams;
import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public class ZipFileExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(ZipFileExtractor.class);

    public void extractZip(File aZipFile, File aTargetDir) throws IOException {
        extractZip(aZipFile, aTargetDir, false);
    }

    /**
     * Extracts the zip into the target dir. When {@code aDeleteStale} is true the target dir is
     * made to mirror the archive: files and directories under it that are not present in the zip
     * are deleted (empty directories are not preserved).
     */
    public void extractZip(File aZipFile, File aTargetDir, boolean aDeleteStale) throws IOException {
        Path      targetRoot   = aTargetDir.toPath().toAbsolutePath().normalize();
        Set<Path> archiveFiles = new HashSet<>();

        try (ZipFile zipFile = new ZipFile(aZipFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (zipEntry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zipFile.getInputStream(zipEntry)) {
                    File file = SafeFiles.createFileGuarded(aTargetDir, zipEntry.getName());
                    makeDirForFile(file);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Extracting {} to {} ...", Strings.forLog(zipEntry.getName()), Strings.forLog(file.getAbsolutePath()));
                    }
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        Streams.copy(in, out);
                    }
                    archiveFiles.add(targetRoot.relativize(file.toPath().toAbsolutePath().normalize()));
                }
            }
        }

        if (aDeleteStale) {
            deleteStale(aTargetDir, targetRoot, archiveFiles);
        }
    }

    private void deleteStale(File aTargetDir, Path aTargetRoot, Set<Path> aArchiveFiles) throws IOException {
        if (!aTargetDir.exists()) {
            return;
        }
        String canonicalRoot = aTargetDir.getCanonicalPath();

        Files.walkFileTree(aTargetRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path aFile, BasicFileAttributes aAttrs) throws IOException {
                Path relative = aTargetRoot.relativize(aFile.toAbsolutePath().normalize());
                if (!aArchiveFiles.contains(relative)) {
                    deleteGuarded(aFile, canonicalRoot);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path aDir, IOException aExc) throws IOException {
                if (aExc != null) {
                    throw aExc;
                }
                if (!aDir.equals(aTargetRoot) && isEmptyDir(aDir)) {
                    deleteGuarded(aDir, canonicalRoot);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isEmptyDir(Path aDir) throws IOException {
        try (Stream<Path> children = Files.list(aDir)) {
            return children.findAny().isEmpty();
        }
    }

    private void deleteGuarded(Path aPath, String aCanonicalRoot) throws IOException {
        String canonical = aPath.toFile().getCanonicalPath();
        if (!canonical.equals(aCanonicalRoot) && !canonical.startsWith(aCanonicalRoot + File.separator)) {
            throw new IllegalStateException("Refusing to delete " + canonical + " outside of " + aCanonicalRoot);
        }
        LOG.info("Deleting stale {} ...", canonical);
        Files.delete(aPath);
    }

    private void makeDirForFile(File aFile) throws IOException {
        File dir = aFile.getParentFile();
        if (dir.exists()) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Creating directory {} ...", Strings.forLog(dir.getAbsolutePath()));
        }
        Files.createDirectories(dir.toPath());
    }


}
