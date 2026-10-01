package com.payneteasy.dcagent.core.modules.zipversion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Steps 5–6 of {@code zip-archive-version}, under the directory lock: a version directory appears
 * complete or not at all, and is never written over.
 * <ul>
 *     <li>{@code <dir>/<version>} missing → leftovers of a crash ({@code .<version>.new-*}) removed,
 *     the version published: staging → digest file → one rename;</li>
 *     <li>a real directory with {@code .<version>.sha256} equal to the digest → {@link Result#PRESENT}
 *     (fsync of {@code dir} again: an earlier call may have failed exactly there);</li>
 *     <li>anything else → 409, nothing written.</li>
 * </ul>
 * After a crash there may be a digest without a version (overwritten by the next call), never a
 * version without a digest (a permanent 409): the digest is renamed and {@code dir} synced first.
 */
public final class VersionPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VersionPublisher.class);

    private static final int DIGEST_FILE_LIMIT = 128;

    public enum Result { PUBLISHED, PRESENT }

    private final Durability durability;

    public VersionPublisher(Durability aDurability) {
        durability = aDurability;
    }

    /**
     * {@code dir} itself, created if missing (only it — its parent must exist — mode 0755), and its
     * parent synced in every call: a retry after a failed sync of the parent must not skip it.
     *
     * @return the real path of {@code dir}, the key of its lock
     */
    public Path prepareDir(Path aDir) throws IOException {
        Path parent = aDir.getParent();
        if (!Files.isDirectory(aDir)) {
            try {
                VersionFiles.createNewDirectory(aDir);
                LOG.info("Created {}", aDir);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(aDir)) {
                    throw new IOException("dir " + aDir + " exists and is not a directory");
                }
            } catch (NoSuchFileException e) {
                throw new IOException("the parent of dir " + aDir + " does not exist");
            }
        }
        VersionFiles.syncDir(parent, "dir-parent", durability);
        return aDir.toRealPath();
    }

    /** @param aRealDir {@code dir} as returned by {@link #prepareDir}, its lock held by the caller */
    public Result publish(Path aRealDir, String aVersion, VersionArchive aArchive) throws IOException {
        Path                target     = aRealDir.resolve(aVersion);
        String              digestName = digestFileName(aVersion);
        BasicFileAttributes existing   = lstat(target);

        if (existing != null) {
            if (!existing.isDirectory() || existing.isSymbolicLink()) {
                throw Problems.conflict("version " + aVersion + " exists and is not a directory");
            }
            byte[] digestFile = VersionFiles.readSmall(aRealDir.resolve(digestName), DIGEST_FILE_LIMIT);
            String published  = digestFile == null ? null : Digests.parseFile(digestFile);
            if (published == null) {
                throw Problems.conflict("version " + aVersion + " exists without a digest; it was not published by this command");
            }
            if (!published.equals(aArchive.digest())) {
                throw Problems.conflict("version " + aVersion + " is already published with other contents");
            }
            VersionFiles.syncDir(aRealDir, "present", durability);
            return Result.PRESENT;
        }

        removeLeftovers(aRealDir, aVersion);
        Path staging = aRealDir.resolve(stagingName(aVersion));
        boolean moved = false;
        try {
            stage(staging, aArchive);
            VersionFiles.writeAtomically(aRealDir, digestName, Digests.fileContents(aArchive.digest()), "digest", durability);
            // The lock and the single-writer rule guarantee the target is absent: Files.move leaves an
            // existing target to the implementation, rename(2) silently replaces an empty directory.
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
        } finally {
            if (!moved) {
                VersionFiles.deleteTreeQuietly(staging);
            }
        }
        VersionFiles.syncDir(aRealDir, "version-moved", durability);
        LOG.info("Published version {} into {}: {} files, {} bytes, sha256 {}", aVersion, aRealDir,
                aArchive.files().size(), aArchive.totalBytes(), aArchive.digest());
        return Result.PUBLISHED;
    }

    static String digestFileName(String aVersion) {
        return "." + aVersion + ".sha256";
    }

    /**
     * Pass 2: every file written with {@code CREATE_NEW}, every directory created one segment at a
     * time (so there is never a link inside to follow), contents digested again from the bytes that
     * went to disk — the digest file must describe what is on disk, a later retry compares against it.
     */
    private void stage(Path aStaging, VersionArchive aArchive) throws IOException {
        VersionFiles.createNewDirectory(aStaging);
        Set<Path>           dirs    = new LinkedHashSet<>();
        Map<String, String> written = new LinkedHashMap<>();
        byte[]              buffer  = new byte[64 * 1024];
        dirs.add(aStaging);

        for (VersionArchive.FileEntry file : aArchive.files()) {
            Path parent = aStaging;
            String[] segments = file.path().split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                parent = parent.resolve(segments[i]);
                if (dirs.add(parent)) {
                    VersionFiles.createNewDirectory(parent);
                }
            }
            Path          target = parent.resolve(segments[segments.length - 1]);
            MessageDigest sha    = Digests.sha256();
            long          size   = 0;
            try (InputStream in = aArchive.open(file); FileChannel out = VersionFiles.createNewFile(target)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    size += count;
                    if (size > file.size()) {
                        throw stagedDiffers(file);
                    }
                    sha.update(buffer, 0, count);
                    ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, count);
                    while (chunk.hasRemaining()) {
                        out.write(chunk);
                    }
                }
                VersionFiles.sync(() -> durability.syncFile(out, "staging-file"), "staging-file");
            }
            String hex = Digests.hex(sha.digest());
            if (size != file.size() || !hex.equals(file.sha256())) {
                throw stagedDiffers(file);
            }
            written.put(file.path(), hex);
        }
        if (!Digests.treeDigest(written).equals(aArchive.digest())) {
            throw new IllegalStateException("staged tree digest differs from the upload");
        }

        List<Path> deepestFirst = new ArrayList<>(dirs);
        Collections.reverse(deepestFirst);
        for (Path dir : deepestFirst) {
            VersionFiles.syncDir(dir, "staging-dir", durability);
        }
    }

    private static IllegalStateException stagedDiffers(VersionArchive.FileEntry aFile) {
        return new IllegalStateException("staged contents of " + aFile.path() + " differ from what was checked; nothing published");
    }

    /** The staging name of a version: {@code .<version>.new-<16 hex>}. */
    static String stagingName(String aVersion) {
        return "." + aVersion + ".new-" + VersionFiles.randomSuffix();
    }

    /**
     * {@code .<version>.new-<16 hex>} of a crashed call, matched exactly — a prefix would also take
     * the digest {@code .v1.new-release.sha256} of another valid version {@code v1.new-release}.
     * Links unlinked, real directories walked, nothing followed.
     */
    private static void removeLeftovers(Path aRealDir, String aVersion) throws IOException {
        Pattern staging = Pattern.compile("^" + Pattern.quote("." + aVersion + ".new-") + "[0-9a-f]{16}$");
        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aRealDir, entry -> staging.matcher(entry.getFileName().toString()).matches())) {
            stream.forEach(leftovers::add);
        }
        for (Path leftover : leftovers) {
            LOG.warn("Removing {} left by an interrupted publication", leftover);
            VersionFiles.deleteTree(leftover);
        }
    }

    private static BasicFileAttributes lstat(Path aPath) throws IOException {
        try {
            return Files.readAttributes(aPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }
}
