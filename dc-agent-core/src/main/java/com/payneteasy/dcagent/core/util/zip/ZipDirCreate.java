package com.payneteasy.dcagent.core.util.zip;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.file.Files.newOutputStream;

/**
 * Packs a directory tree into a ZIP (files only, directories follow symbolic links). A file that
 * cannot be read or a directory that cannot be listed fails the whole packing with the list of all
 * such paths and leaves the target file untouched — nothing is skipped silently.
 */
public class ZipDirCreate {

    private static final Logger LOG = LoggerFactory.getLogger( ZipDirCreate.class );

    /** File system access, replaceable in tests to fail where permissions cannot. */
    interface Io {
        List<Path> list(Path aDir) throws IOException;

        void copy(Path aFile, OutputStream aOut) throws IOException;
    }

    private static final Io FILES = new Io() {
        @Override
        public List<Path> list(Path aDir) throws IOException {
            List<Path> entries = new ArrayList<>();
            // not File.listFiles: it turns a listing error into "no entries"
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(aDir)) {
                for (Path entry : stream) {
                    entries.add(entry);
                }
            }
            return entries;
        }

        @Override
        public void copy(Path aFile, OutputStream aOut) throws IOException {
            Files.copy(aFile, aOut);
        }
    };

    private Io io = FILES;

    ZipDirCreate io(Io aIo) {
        io = aIo;
        return this;
    }


    private File   baseDir;
    private String firstSegment = "";

    public ZipDirCreate baseDir(File baseDir) {
        this.baseDir = baseDir;
        return this;
    }

    public ZipDirCreate firstSegment(String firstSegment) {
        this.firstSegment = firstSegment;
        return this;
    }

    public TempFile createZipFile(TempFile aTempFile) {
        createZipFile(aTempFile.getFile());
        return aTempFile;
    }

    /** @throws UnreadableTaskFilesException when an entry cannot be read; then nothing is written */
    public File createZipFile(File aFile) {
        Objects.requireNonNull(baseDir, "Base dir is null");

        // streamed into a sibling file: every problem is reported, memory stays flat, and the
        // target is replaced only by a complete ZIP
        Path         base     = baseDir.toPath();
        Path         target   = aFile.toPath().toAbsolutePath();
        List<String> problems = new ArrayList<>();
        Path         partial;
        try {
            partial = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".partial");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temp file next to " + target, e);
        }
        try {
            try (ZipOutputStream out = new ZipOutputStream(newOutputStream(partial))) {
                collect(base, base, out, problems);
            }
            if (!problems.isEmpty()) {
                throw new UnreadableTaskFilesException(baseDir.getAbsolutePath(), problems);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return aFile;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write zip to file " + target, e);
        } finally {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException e) {
                LOG.warn("Cannot delete {}", partial, e);
            }
        }
    }

    private void collect(Path aBase, Path aDir, ZipOutputStream aOut, List<String> aProblems) throws IOException {
        List<Path> entries;
        try {
            entries = new ArrayList<>(io.list(aDir));
        } catch (IOException | RuntimeException e) {
            aProblems.add(relative(aBase, aDir) + "/ (cannot list: " + e.getClass().getSimpleName() + ")");
            return;
        }
        entries.sort(null);

        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                collect(aBase, entry, aOut, aProblems);
            } else if (Files.isRegularFile(entry) && Files.isReadable(entry)) {
                aOut.putNextEntry(new ZipEntry(getEntryName(aBase, entry)));
                try {
                    io.copy(entry, aOut);
                } catch (IOException | RuntimeException e) {
                    // the partial ZIP is thrown away, keep going to report every path
                    aProblems.add(relative(aBase, entry) + " (cannot read: " + e.getClass().getSimpleName() + ")");
                }
                aOut.closeEntry();
            } else {
                aProblems.add(relative(aBase, entry) + (Files.exists(entry) ? " (cannot read)" : " (dangling symbolic link)"));
            }
        }
    }

    private static String relative(Path aBase, Path aPath) {
        String relative = aBase.relativize(aPath).toString();
        return relative.isEmpty() ? "." : relative;
    }

    private String getEntryName(Path aBase, Path aFile) {
        // the same names as before: firstSegment + "/" + relative path (a leading '/' without firstSegment)
        String entryName = firstSegment + "/" + aBase.relativize(aFile).toString().replace(File.separatorChar, '/');
        LOG.trace("Entry is {}\n for file {}", entryName, aFile);
        return entryName;
    }
}
