package com.payneteasy.dcagent.core.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/**
 * A durable, all-or-nothing write of a small file that may hold secrets (a command config):
 * <ol>
 *     <li>a temporary sibling {@code .dc-agent-tmp-<random>} (not {@code .json}/{@code .yml}: never
 *     taken for a config) is created <b>already {@code 0600}</b> — no moment where others can read it;</li>
 *     <li>the contents are written; the final mode and owner (those of the file it replaces, or
 *     {@code 0600} for a new one) are set; the file is {@code fsync}ed;</li>
 *     <li>it is published: {@link #replace} renames it over the target (a link at the target's place
 *     is replaced, not followed), {@link #createNew} hard-links it to the target, which fails when the
 *     target exists — {@code ATOMIC_MOVE} would silently replace it;</li>
 *     <li>the directory is {@code fsync}ed, so a power loss cannot bring back the old file.</li>
 * </ol>
 * On a failure before publishing, the temporary file is removed.
 */
public final class AtomicFiles {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private AtomicFiles() {
    }

    /** {@code aTarget} gets {@code aContents}; an existing target keeps its mode and owner. */
    public static void replace(Path aTarget, byte[] aContents) throws IOException {
        replace(aTarget, aContents, aTarget);
    }

    /**
     * {@code aTarget} gets {@code aContents} with the mode and owner of {@code aAttributesFrom} (a YAML
     * config turned into JSON keeps the YAML file's), {@code 0600} when that does not exist.
     */
    public static void replace(Path aTarget, byte[] aContents, Path aAttributesFrom) throws IOException {
        PosixFileAttributes previous = posixAttributes(aAttributesFrom);
        Path temp = writeTemp(aTarget, aContents, previous);
        boolean published = false;
        try {
            Files.move(temp, aTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temp);
            }
        }
        syncDir(aTarget.getParent());
    }

    /**
     * Creates {@code aTarget} ({@code 0600}) unless it exists.
     *
     * @return false when the target already exists (nothing is changed)
     */
    public static boolean createNew(Path aTarget, byte[] aContents) throws IOException {
        Path temp = writeTemp(aTarget, aContents, null);
        try {
            Files.createLink(aTarget, temp);
        } catch (FileAlreadyExistsException e) {
            return false;
        } finally {
            Files.deleteIfExists(temp);
        }
        syncDir(aTarget.getParent());
        return true;
    }

    /** Makes a removal or a rename in {@code aDir} durable. */
    public static void syncDir(Path aDir) throws IOException {
        try (FileChannel channel = FileChannel.open(aDir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static Path writeTemp(Path aTarget, byte[] aContents, PosixFileAttributes aPrevious) throws IOException {
        Path    temp    = aTarget.resolveSibling(".dc-agent-tmp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        boolean posix   = Files.getFileStore(aTarget.getParent()).supportsFileAttributeView(PosixFileAttributeView.class);
        boolean written = false;
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
        try (FileChannel channel = FileChannel.open(temp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), attributes)) {
            ByteBuffer buffer = ByteBuffer.wrap(aContents);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (posix) {
                PosixFileAttributeView view = Files.getFileAttributeView(temp, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (aPrevious != null) {
                    if (!view.readAttributes().owner().equals(aPrevious.owner())) {
                        view.setOwner(aPrevious.owner());
                    }
                    if (!view.readAttributes().group().equals(aPrevious.group())) {
                        view.setGroup(aPrevious.group());
                    }
                    view.setPermissions(aPrevious.permissions());
                } else {
                    view.setPermissions(OWNER_ONLY); // the umask may have narrowed the creation mode further
                }
            }
            channel.force(true);
            written = true;
        } finally {
            if (!written) {
                Files.deleteIfExists(temp);
            }
        }
        return temp;
    }

    private static PosixFileAttributes posixAttributes(Path aFile) throws IOException {
        if (!Files.exists(aFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        PosixFileAttributeView view = Files.getFileAttributeView(aFile, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return view != null ? view.readAttributes() : null;
    }
}
