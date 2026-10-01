package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/**
 * File operations of {@code zip-archive-version} inside {@code dir}, where the agent is the only
 * writer: nothing here follows a symbolic link, every temporary name starts with a dot, modes are
 * set explicitly (files {@code 0644}, directories {@code 0755}) rather than left to the umask —
 * the reading service runs as another user.
 */
final class VersionFiles {

    private static final Logger LOG = LoggerFactory.getLogger(VersionFiles.class);

    static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-r--r--");
    static final Set<PosixFilePermission> DIR_MODE  = PosixFilePermissions.fromString("rwxr-xr-x");

    private static final SecureRandom RANDOM = new SecureRandom();

    private VersionFiles() {
    }

    /**
     * {@code aDir/aName} for a name that must stay one entry directly in {@code aDir} (a version, a
     * pointer, a staged path segment — all checked by their own rules before): refused otherwise,
     * so no name can lead out of {@code aDir}, whatever reaches this point.
     */
    static Path child(Path aDir, String aName) {
        if (aName.isEmpty() || ".".equals(aName) || "..".equals(aName) || aName.indexOf('/') >= 0
                || aName.indexOf('\\') >= 0 || aName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("not a single name inside " + aDir);
        }
        Path dir   = aDir.normalize();
        Path child = dir.resolve(aName).normalize();
        if (!child.startsWith(dir) || !dir.equals(child.getParent())) {
            throw new IllegalArgumentException("not a single name inside " + aDir);
        }
        return child;
    }

    static String randomSuffix() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A new file: {@code CREATE_NEW} fails on anything already there, a link included. */
    static FileChannel createNewFile(Path aFile) throws IOException {
        FileChannel channel = FileChannel.open(aFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            setMode(aFile, FILE_MODE);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return channel;
    }

    /** A new directory: {@code createDirectory} fails on anything already there, a link included. */
    static void createNewDirectory(Path aDir) throws IOException {
        Files.createDirectory(aDir);
        setMode(aDir, DIR_MODE);
    }

    static void setMode(Path aPath, Set<PosixFilePermission> aMode) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(aPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            view.setPermissions(aMode);
        }
    }

    /**
     * {@code aContents} as {@code <dir>/<name>}, never seen partly written: a temporary sibling
     * {@code .<name>.tmp-<random>} → fsync ({@code <mark>-file}) → one atomic rename over the target
     * → fsync of {@code dir} ({@code <mark>-moved}). The temporary file is removed on a failure before the rename.
     * A rename over an existing file replaces it atomically ({@code rename(2)}), a link by the link itself.
     */
    static void writeAtomically(Path aDir, String aName, byte[] aContents, String aMark, Durability aDurability) throws IOException {
        Path temp   = child(aDir, "." + aName + ".tmp-" + randomSuffix());
        Path target = child(aDir, aName);
        boolean moved = false;
        try {
            try (FileChannel channel = createNewFile(temp)) {
                ByteBuffer buffer = ByteBuffer.wrap(aContents);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                sync(() -> aDurability.syncFile(channel, aMark + "-file"), aMark + "-file");
            }
            // ATOMIC_MOVE forbids a copy-and-delete fallback
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                deleteQuietly(temp);
            }
        }
        syncDir(aDir, aMark + "-moved", aDurability);
    }

    static void syncDir(Path aDir, String aMark, Durability aDurability) {
        sync(() -> aDurability.syncDir(aDir, aMark), aMark);
    }

    interface IoAction {
        void run() throws IOException;
    }

    static void sync(IoAction aSync, String aMark) {
        try {
            aSync.run();
        } catch (IOException e) {
            throw new DurabilityException(aMark, e);
        }
    }

    /** {@code dir/<name>} up to {@code aLimit} bytes without following a link; null when missing, a link or longer. */
    static byte[] readSmall(Path aFile, int aLimit) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(aFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
        if (!attributes.isRegularFile()) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(aFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(aLimit + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // fill
            }
            if (buffer.position() > aLimit) {
                return null;
            }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            return bytes;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            // ELOOP: replaced by a link between the lstat and the open
            if (Files.isSymbolicLink(aFile)) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Removes {@code aPath} and, when it is a real directory, everything inside — {@code lstat}
     * every entry: a link is unlinked, never followed, a directory is walked with the same rule.
     */
    static void deleteTree(Path aPath) throws IOException {
        // walkFileTree without FOLLOW_LINKS reports a link (the start included) as a file
        Files.walkFileTree(aPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path aFile, BasicFileAttributes aAttributes) throws IOException {
                Files.delete(aFile);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path aDir, IOException aError) throws IOException {
                if (aError != null) {
                    throw aError;
                }
                Files.delete(aDir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTreeQuietly(Path aPath) {
        try {
            deleteTree(aPath);
        } catch (NoSuchFileException e) {
            // already gone
        } catch (IOException e) {
            LOG.warn("Cannot remove {}", Strings.forLog(aPath.toString()), e);
        }
    }

    static void deleteQuietly(Path aFile) {
        try {
            Files.deleteIfExists(aFile);
        } catch (IOException e) {
            LOG.warn("Cannot remove {}", Strings.forLog(aFile.toString()), e);
        }
    }
}
