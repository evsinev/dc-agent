package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;

/**
 * The uploaded ZIP on its way to pass 1: a temp file outside {@code dir}, mode 0600, at most
 * {@code maxUploadBytes} (else 413), deleted when closed. Leftovers — an agent killed mid-upload —
 * are removed at startup by {@link #sweep}: only regular files with the prefix, owned by this
 * process's user and older than the given age, so a file of an older agent process still running
 * through a restart, or a file of another user in a shared {@code /tmp}, is left alone.
 */
public final class UploadTempFiles {

    private static final Logger LOG = LoggerFactory.getLogger(UploadTempFiles.class);

    static final String PREFIX = "dc-agent-zav-";

    private final Path dir;

    public UploadTempFiles(Path aDir) {
        dir = aDir;
    }

    public Path dir() {
        return dir;
    }

    public Upload create() throws IOException {
        Path file = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile(dir, PREFIX, ".zip", ownerOnly())
                : Files.createTempFile(dir, PREFIX, ".zip");
        return new Upload(file);
    }

    private static FileAttribute<?> ownerOnly() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }

    /** One upload; {@link #close} deletes the file and may be called more than once. */
    public static final class Upload implements Closeable {

        private final Path file;

        private Upload(Path aFile) {
            file = aFile;
        }

        public Path path() {
            return file;
        }

        /** Copies at most {@code aLimit} bytes; one more → 413 (the request is not read further). */
        public void copyFrom(InputStream aIn, long aLimit) throws IOException {
            byte[] buffer = new byte[64 * 1024];
            long   total  = 0;
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = aIn.read(buffer)) != -1) {
                    total += count;
                    if (total > aLimit) {
                        throw tooLarge(aLimit);
                    }
                    out.write(buffer, 0, count);
                }
            }
        }

        @Override
        public void close() {
            VersionFiles.deleteQuietly(file);
        }
    }

    public static ProblemException tooLarge(long aLimit) {
        return new ProblemException(413, "the upload exceeds maxUploadBytes (" + aLimit + " bytes)");
    }

    /** @return how many leftovers were removed */
    public int sweep(Duration aOlderThan) {
        int removed = 0;
        try {
            UserPrincipal me     = currentOwner();
            Instant       cutoff = Instant.now().minus(aOlderThan);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*")) {
                for (Path entry : stream) {
                    BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile()
                            || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)
                            || !me.equals(Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS))) {
                        continue;
                    }
                    Files.deleteIfExists(entry);
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot sweep old uploads in {}", dir, e);
        }
        if (removed > 0) {
            LOG.info("Removed {} upload(s) older than {} left in {}", removed, aOlderThan, dir);
        }
        return removed;
    }

    /** The owner of a file this process creates — the user the agent runs as. */
    private UserPrincipal currentOwner() throws IOException {
        Path probe = Files.createTempFile(dir, ".dc-agent-owner-", ".tmp");
        try {
            return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
