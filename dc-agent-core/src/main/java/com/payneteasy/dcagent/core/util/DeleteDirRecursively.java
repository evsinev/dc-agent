package com.payneteasy.dcagent.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Deletes a file tree inside a sentinel directory without ever following a symbolic link: a link
 * (to a directory, to a file, dangling) is removed itself, its target stays.
 *
 * <p>The start is resolved physically — its parent through links and {@code ..}, its last
 * component not — and must lie strictly inside the real path of the sentinel (compared by path
 * components, so {@code /tmp/dc} is not a prefix of {@code /tmp/dc-other}).
 *
 * <p>Where the JDK offers {@link SecureDirectoryStream} (Linux) the tree is deleted relative to
 * open directory descriptors, descending from the sentinel with {@code NOFOLLOW_LINKS}: a directory
 * swapped for a link while the agent deletes fails the deletion instead of redirecting it. Elsewhere
 * (macOS, development only) it falls back to a path walk that does not follow links but is open to
 * such a race. The sentinel itself and the directories above it must be trusted.
 */
public class DeleteDirRecursively {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteDirRecursively.class);

    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    /** Test hooks around opening a directory of the tree (the secure deletion only). */
    interface Hooks {

        void beforeOpen(Path aDir) throws IOException;

        void afterOpen(Path aDir) throws IOException;
    }

    private static final Hooks NO_HOOKS = new Hooks() {
        @Override
        public void beforeOpen(Path aDir) {
            // no hook
        }

        @Override
        public void afterOpen(Path aDir) {
            // no hook
        }
    };

    private final File    sentinelDir;
    private final Hooks   hooks;
    private final boolean secureIfAvailable;

    public DeleteDirRecursively(File aSentinelDir) {
        this(aSentinelDir, NO_HOOKS, true);
    }

    /**
     * @param aSecureIfAvailable false forces the path walk (tests run both implementations on Linux)
     */
    DeleteDirRecursively(File aSentinelDir, Hooks aHooks, boolean aSecureIfAvailable) {
        sentinelDir       = aSentinelDir;
        hooks             = aHooks;
        secureIfAvailable = aSecureIfAvailable;
    }

    public void deleteDir(File aDir) {
        Path sentinel = sentinelRealPath();
        Path start    = startRealPath(aDir, sentinel);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting dir {} ...", Strings.forLog(start.toString()));
        }
        try {
            if (!secureIfAvailable || !deleteSecurely(sentinel, start)) {
                deleteByWalk(start);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + start + ": " + e.getMessage(), e);
        }
    }

    public void deleteDirIfExists(File aDir) {
        if (Files.exists(aDir.toPath(), NOFOLLOW)) {
            deleteDir(aDir);
        }
    }

    /** Whether this platform deletes relative to directory descriptors. */
    boolean usesSecureDirectoryStream() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sentinelRealPath())) {
            return stream instanceof SecureDirectoryStream;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open sentinel dir " + sentinelDir, e);
        }
    }

    private Path sentinelRealPath() {
        try {
            return sentinelDir.toPath().toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Sentinel dir " + sentinelDir.getAbsolutePath() + " cannot be resolved: " + e, e);
        }
    }

    private static Path startRealPath(File aDir, Path aSentinel) {
        Path absolute = aDir.toPath().toAbsolutePath();
        Path name     = absolute.getFileName();
        if (name == null || ".".equals(name.toString()) || "..".equals(name.toString())) {
            throw new IllegalStateException("You are going to delete " + absolute + ": the last component must be a name");
        }

        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalStateException("You are going to delete " + absolute + ": it has no parent directory");
        }
        Path start;
        try {
            // not normalize(): 'link/..' is the parent of the link's target, not the link's directory
            start = parent.toRealPath().resolve(name);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve the parent of " + absolute + ": " + e, e);
        }

        if (!start.startsWith(aSentinel) || start.equals(aSentinel)) {
            throw new IllegalStateException("You are going to delete " + start + " (" + absolute + ") outside of " + aSentinel
                    + "; only paths strictly inside the sentinel dir can be deleted");
        }
        return start;
    }

    /**
     * @return false when the platform has no {@link SecureDirectoryStream}
     */
    private boolean deleteSecurely(Path aSentinel, Path aStart) throws IOException {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            DirectoryStream<Path> root = Files.newDirectoryStream(aSentinel);
            if (!(root instanceof SecureDirectoryStream)) {
                root.close();
                return false;
            }
            SecureDirectoryStream<Path> dir = (SecureDirectoryStream<Path>) root;
            opened.add(dir);

            Path relative = aSentinel.relativize(aStart);
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                dir = dir.newDirectoryStream(relative.getName(i), NOFOLLOW);
                opened.add(dir);
            }
            deleteEntry(dir, relative.getFileName(), aStart);
            return true;
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) {
                opened.get(i).close();
            }
        }
    }

    private void deleteEntry(SecureDirectoryStream<Path> aParent, Path aName, Path aPath) throws IOException {
        try {
            deleteEntryUnchecked(aParent, aName, aPath);
        } catch (EntryFailure e) {
            throw e;
        } catch (IOException e) {
            throw new EntryFailure(aPath, e);
        }
    }

    /** The first entry that could not be deleted, with its full path (the JDK reports only the name). */
    private static final class EntryFailure extends IOException {

        EntryFailure(Path aPath, IOException aCause) {
            super("cannot delete " + aPath + " (changed while deleting?): " + aCause, aCause);
        }
    }

    private void deleteEntryUnchecked(SecureDirectoryStream<Path> aParent, Path aName, Path aPath) throws IOException {
        BasicFileAttributes attributes = aParent.getFileAttributeView(aName, BasicFileAttributeView.class, NOFOLLOW).readAttributes();
        if (!attributes.isDirectory()) {
            aParent.deleteFile(aName);
            return;
        }

        hooks.beforeOpen(aPath);
        List<Path> names = new ArrayList<>();
        try (SecureDirectoryStream<Path> dir = aParent.newDirectoryStream(aName, NOFOLLOW)) {
            hooks.afterOpen(aPath);
            for (Path entry : dir) {
                names.add(entry.getFileName());
            }
            for (Path name : names) {
                deleteEntry(dir, name, aPath.resolve(name));
            }
        }
        aParent.deleteDirectory(aName);
    }

    private static void deleteByWalk(Path aStart) throws IOException {
        Files.walkFileTree(aStart, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path aFile, BasicFileAttributes aAttributes) throws IOException {
                deleteInside(aStart, aFile);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path aDir, IOException aException) throws IOException {
                if (aException != null) {
                    throw aException;
                }
                deleteInside(aStart, aDir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteInside(Path aStart, Path aPath) throws IOException {
        if (!aPath.startsWith(aStart)) {
            throw new IllegalStateException("Refusing to delete " + aPath + " outside of " + aStart);
        }
        Files.delete(aPath);
    }
}
