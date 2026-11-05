package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.modules.docker.preflight.PathAttributes;
import com.payneteasy.dcagent.core.util.Strings;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deletes under the root of a {@code sync: MIRROR} dirConfig what the task does not have. One
 * {@link #plan()} serves DOCKER_CHECK (only logged) and DOCKER_PUSH (built before the copy,
 * {@link #execute executed} after it), so on an unchanged host push deletes exactly what check
 * listed.
 *
 * <p>Rules, for every entry under the root (names compared exactly):
 * <ul>
 *   <li>a path or object of another volume of the task, or {@code keep} match — left with its
 *       whole subtree;</li>
 *   <li>in the task with the same kind (regular file / directory) — kept, a directory is walked;
 *       another kind (a symbolic link, a file where the task has a directory, …) is an error;</li>
 *   <li>not in the task but equal to a task name up to case and Unicode normalization — an error
 *       (on a case-insensitive file system it <i>is</i> the task's file);</li>
 *   <li>otherwise deleted: a file or a symbolic link itself (never its target), a directory when
 *       nothing is left in it — unless it is on the way to another volume's path;</li>
 *   <li>a directory on another device (a mount point inside the root) is an error.</li>
 * </ul>
 *
 * <p>The root is opened from {@code /} component by component with {@code NOFOLLOW_LINKS}, and
 * everything else relative to open directories ({@link SecureDirectoryStream}, Linux): a directory
 * swapped for a symbolic link while the agent works fails instead of leading outside. Every opened
 * directory is checked to be the object whose attributes were checked (planning) or walked
 * (execution), and each entry is deleted only if it is still the object the plan saw. Without {@code SecureDirectoryStream}
 * (macOS, development only) the same walk goes by paths with {@code NOFOLLOW_LINKS} and stays open
 * to such a race.
 */
public final class MirrorPrune implements AutoCloseable {

    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    /** Test hook before a directory under the root is opened. */
    interface Hooks {
        void beforeOpen(Path aRelative) throws IOException;
    }

    /** One entry to delete; {@code relative} to the root. */
    public record Deletion(Path relative, Path absolute, boolean directory, Object fileKey) {

        /** The absolute path for a log line, a directory with a trailing {@code /}. */
        public String forLog() {
            return Strings.forLog(absolute + (directory ? "/" : ""));
        }
    }

    private final MirrorTarget   target;
    private final Hooks          hooks;
    private final Dir            root;       // null: nothing to delete
    private final long           rootDevice;
    private final Map<Path, Object> directoryKeys = new HashMap<>(); // walked directories, relative → fileKey
    private       List<Deletion> plan;

    private MirrorPrune(MirrorTarget aTarget, Hooks aHooks, Dir aRoot, long aRootDevice) {
        target     = aTarget;
        hooks      = aHooks;
        root       = aRoot;
        rootDevice = aRootDevice;
    }

    public static MirrorPrune open(MirrorTarget aTarget) {
        return open(aTarget, null, true);
    }

    /** @param aSecureIfAvailable false forces the path walk (tests run both on Linux) */
    static MirrorPrune open(MirrorTarget aTarget, Hooks aHooks, boolean aSecureIfAvailable) {
        Path physical = aTarget.physicalRoot();
        if (physical == null) {
            return new MirrorPrune(aTarget, aHooks, null, 0);
        }
        PathAttributes attributes;
        try {
            attributes = PathAttributes.LSTAT.read(physical);
        } catch (IOException e) {
            throw failure(aTarget, "cannot read " + physical, e);
        }
        if (attributes == null) {
            return new MirrorPrune(aTarget, aHooks, null, 0);
        }
        if (!attributes.isDirectory()) {
            throw failure(aTarget, physical + " is not a directory", null);
        }
        try {
            return new MirrorPrune(aTarget, aHooks, openRoot(physical, aSecureIfAvailable), attributes.device());
        } catch (IOException e) {
            throw failure(aTarget, "cannot open " + physical + " without following symbolic links (changed meanwhile?)", e);
        }
    }

    private static Dir openRoot(Path aPhysical, boolean aSecureIfAvailable) throws IOException {
        Dir dir = Dir.top(aPhysical.getRoot(), aSecureIfAvailable);
        try {
            for (Path name : aPhysical) {
                Dir next = dir.open(name.toString());
                dir.close();
                dir = next;
            }
            return dir;
        } catch (IOException | RuntimeException e) {
            dir.close();
            throw e;
        }
    }

    /** Whether this instance works relative to directory descriptors. */
    boolean usesSecureDirectoryStream() {
        return root instanceof SecureDir;
    }

    /** What to delete, children before their directory. Computed once. */
    public List<Deletion> plan() {
        if (plan == null) {
            List<Deletion> deletions = new ArrayList<>();
            if (root != null) {
                try {
                    visit(root, Path.of(""), target.configDir(), deletions);
                } catch (IOException e) {
                    throw failure(target, e.getMessage(), e);
                }
            }
            plan = List.copyOf(deletions);
        }
        return plan;
    }

    /**
     * Deletes {@link #plan()}. An entry that is no longer the object the plan saw, or a directory
     * that is not empty any more, fails the push.
     */
    public void execute(Consumer<Deletion> aDeleted) {
        for (Deletion deletion : plan()) {
            try {
                delete(deletion);
            } catch (IOException e) {
                throw failure(target, "cannot delete " + deletion.forLog() + " (changed meanwhile?): " + e.getMessage(), e);
            }
            aDeleted.accept(deletion);
        }
    }

    @Override
    public void close() {
        if (root != null) {
            try {
                root.close();
            } catch (IOException e) {
                throw failure(target, "cannot close " + target.physicalRoot(), e);
            }
        }
    }

    /** @return whether every entry of the directory is planned for deletion */
    private boolean visit(Dir aDir, Path aRelative, Path aTaskDir, List<Deletion> aDeletions) throws IOException {
        Map<String, Boolean> task   = aTaskDir == null ? Map.of() : taskEntries(aTaskDir);
        Map<String, String>  folded = new HashMap<>();
        for (String name : task.keySet()) {
            folded.put(fold(name), name);
        }

        List<String> names = aDir.names();
        names.sort(null);

        boolean allGone = true;
        for (String name : names) {
            Path                relative   = aRelative.resolve(name);
            BasicFileAttributes attributes = aDir.attributes(name);

            if (isProtected(relative, attributes.fileKey()) || target.keep().matches(relative)) {
                allGone = false;
                continue;
            }

            Boolean taskDirectory = task.get(name);
            if (taskDirectory != null) {
                if (taskDirectory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
                    throw new IOException(absolute(relative) + " is " + kind(attributes) + ", the task has a "
                            + (taskDirectory ? "directory" : "file") + " there; replace it by hand");
                }
                if (taskDirectory) {
                    try (Dir child = openChild(aDir, name, relative, attributes)) {
                        visit(child, relative, aTaskDir.resolve(name), aDeletions);
                    }
                }
                allGone = false;
                continue;
            }

            String twin = folded.get(fold(name));
            if (twin != null) {
                throw new IOException(absolute(relative) + " differs from '" + Strings.forLog(twin)
                        + "' in the task only by case or Unicode normalization; rename or delete it by hand");
            }

            if (attributes.isDirectory()) {
                boolean empty;
                try (Dir child = openChild(aDir, name, relative, attributes)) {
                    empty = visit(child, relative, null, aDeletions);
                }
                if (empty && !isProtectedParent(relative)) {
                    aDeletions.add(new Deletion(relative, target.physicalRoot().resolve(relative), true, attributes.fileKey()));
                } else {
                    allGone = false;
                }
            } else if (isProtectedParent(relative)) {
                // a symbolic link on the way to another volume's path
                allGone = false;
            } else {
                aDeletions.add(new Deletion(relative, target.physicalRoot().resolve(relative), false, attributes.fileKey()));
            }
        }
        return allGone;
    }

    /**
     * Opens a directory entry and makes sure it is the object whose attributes were checked: a
     * directory swapped with another one (a protected one) under the same name between the check
     * and the opening fails the walk.
     */
    private Dir openChild(Dir aParent, String aName, Path aRelative, BasicFileAttributes aChecked) throws IOException {
        if (hooks != null) {
            hooks.beforeOpen(aRelative);
        }
        Dir child = aParent.open(aName);
        try {
            checkOpened(child, aRelative, aChecked);
            return child;
        } catch (IOException | RuntimeException e) {
            // any failure after the opening, the checks' own I/O errors included
            try {
                child.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void checkOpened(Dir aChild, Path aRelative, BasicFileAttributes aChecked) throws IOException {
        if (!Objects.equals(aChild.self().fileKey(), aChecked.fileKey())) {
            throw new IOException(absolute(aRelative) + " changed while walking (swapped with another directory?)");
        }
        PathAttributes attributes = PathAttributes.LSTAT.read(target.physicalRoot().resolve(aRelative));
        if (attributes != null && attributes.device() != rootDevice) {
            throw new IOException(absolute(aRelative) + " is a mount point inside the MIRROR root; MIRROR does not cross file systems");
        }
        directoryKeys.put(aRelative, aChecked.fileKey());
    }

    /** Name → is a directory, for the entries of a directory of the extracted task. */
    private static Map<String, Boolean> taskEntries(Path aTaskDir) throws IOException {
        Map<String, Boolean> entries = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aTaskDir)) {
            for (Path path : stream) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
                if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IOException("the task has " + kind(attributes) + " at " + path);
                }
                entries.put(path.getFileName().toString(), attributes.isDirectory());
            }
        }
        return entries;
    }

    private boolean isProtected(Path aRelative, Object aFileKey) {
        return target.protectedPaths().contains(aRelative) || aFileKey != null && target.protectedKeys().contains(aFileKey);
    }

    private boolean isProtectedParent(Path aRelative) {
        if (target.routePaths().contains(aRelative)) {
            return true;
        }
        for (Path path : target.protectedPaths()) {
            if (path.startsWith(aRelative) && !path.equals(aRelative)) {
                return true;
            }
        }
        return false;
    }

    private void delete(Deletion aDeletion) throws IOException {
        Path      relative = aDeletion.relative();
        List<Dir> opened   = new ArrayList<>();
        try {
            Dir parent = root;
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                parent = parent.open(relative.getName(i).toString());
                opened.add(parent);
                // the directory the plan walked, not another one swapped in under its name since
                if (!Objects.equals(parent.self().fileKey(), directoryKeys.get(relative.subpath(0, i + 1)))) {
                    throw new IOException("its directory " + absolute(relative.subpath(0, i + 1)) + " is not the one the plan walked");
                }
            }
            String              name       = relative.getFileName().toString();
            BasicFileAttributes attributes = parent.attributes(name);
            if (!Objects.equals(attributes.fileKey(), aDeletion.fileKey()) || attributes.isDirectory() != aDeletion.directory()) {
                throw new IOException("it is not the entry the plan saw");
            }
            if (aDeletion.directory()) {
                parent.deleteDirectory(name);
            } else {
                parent.deleteFile(name);
            }
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) {
                opened.get(i).close();
            }
        }
    }

    private String absolute(Path aRelative) {
        return Strings.forLog(target.physicalRoot().resolve(aRelative).toString());
    }

    private static String fold(String aName) {
        return Normalizer.normalize(aName, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String kind(BasicFileAttributes aAttributes) {
        if (aAttributes.isSymbolicLink()) {
            return "a symbolic link";
        }
        if (aAttributes.isDirectory()) {
            return "a directory";
        }
        return aAttributes.isRegularFile() ? "a file" : "a special file";
    }

    private static IllegalStateException failure(MirrorTarget aTarget, String aMessage, Exception aCause) {
        return new IllegalStateException(aTarget.label() + " (sync: MIRROR): " + aMessage + "; nothing more was deleted", aCause);
    }

    /** A directory the walk is in: open descriptor (Linux) or path (fallback). */
    private interface Dir extends Closeable {

        static Dir top(Path aRoot, boolean aSecureIfAvailable) throws IOException {
            DirectoryStream<Path> stream = Files.newDirectoryStream(aRoot);
            if (aSecureIfAvailable && stream instanceof SecureDirectoryStream) {
                return new SecureDir((SecureDirectoryStream<Path>) stream);
            }
            stream.close();
            return new PathDir(aRoot);
        }

        /** Names of the entries; may be called once. */
        List<String> names() throws IOException;

        /** Attributes of an entry, a symbolic link itself. */
        BasicFileAttributes attributes(String aName) throws IOException;

        /** Opens a directory entry; a symbolic link fails. */
        Dir open(String aName) throws IOException;

        /** Attributes of this directory itself (through the open descriptor where there is one). */
        BasicFileAttributes self() throws IOException;

        void deleteFile(String aName) throws IOException;

        void deleteDirectory(String aName) throws IOException;
    }

    private static final class SecureDir implements Dir {

        private final SecureDirectoryStream<Path> stream;

        SecureDir(SecureDirectoryStream<Path> aStream) {
            stream = aStream;
        }

        @Override
        public List<String> names() {
            List<String> names = new ArrayList<>();
            for (Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
            return names;
        }

        @Override
        public BasicFileAttributes attributes(String aName) throws IOException {
            return stream.getFileAttributeView(Path.of(aName), BasicFileAttributeView.class, NOFOLLOW).readAttributes();
        }

        @Override
        public Dir open(String aName) throws IOException {
            return new SecureDir(stream.newDirectoryStream(Path.of(aName), NOFOLLOW));
        }

        @Override
        public BasicFileAttributes self() throws IOException {
            return stream.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
        }

        @Override
        public void deleteFile(String aName) throws IOException {
            stream.deleteFile(Path.of(aName));
        }

        @Override
        public void deleteDirectory(String aName) throws IOException {
            stream.deleteDirectory(Path.of(aName));
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    private static final class PathDir implements Dir {

        private final Path dir;

        PathDir(Path aDir) {
            dir = aDir;
        }

        @Override
        public List<String> names() throws IOException {
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    names.add(entry.getFileName().toString());
                }
            }
            return names;
        }

        @Override
        public BasicFileAttributes attributes(String aName) throws IOException {
            return Files.readAttributes(dir.resolve(aName), BasicFileAttributes.class, NOFOLLOW);
        }

        @Override
        public Dir open(String aName) throws IOException {
            Path child = dir.resolve(aName);
            if (!Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW).isDirectory()) {
                throw new IOException(child + " is not a directory (a symbolic link?)");
            }
            return new PathDir(child);
        }

        @Override
        public BasicFileAttributes self() throws IOException {
            return Files.readAttributes(dir, BasicFileAttributes.class, NOFOLLOW);
        }

        @Override
        public void deleteFile(String aName) throws IOException {
            Files.delete(dir.resolve(aName));
        }

        @Override
        public void deleteDirectory(String aName) throws IOException {
            Files.delete(dir.resolve(aName));
        }

        @Override
        public void close() {
            // nothing is open
        }
    }
}
