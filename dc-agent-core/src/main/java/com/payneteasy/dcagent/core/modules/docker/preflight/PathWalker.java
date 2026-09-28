package com.payneteasy.dcagent.core.modules.docker.preflight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Resolves an absolute path component by component from {@code /} with {@code lstat}, following
 * symbolic links itself, and records every step: which directory was entered, which entry was
 * looked up in it and what that entry is. Unlike {@code toRealPath()} the result shows every
 * directory the path goes <i>through</i>, not only where it ends — a path that passes through a
 * directory owned by a container is dangerous even if it ends elsewhere.
 */
public class PathWalker {

    static final int MAX_SYMBOLIC_LINKS = 40;

    private final PathAttributes.Reader reader;

    public PathWalker(PathAttributes.Reader aReader) {
        reader = aReader;
    }

    /** One lookup: entry {@code child} in the already resolved directory {@code parent}. */
    public static final class Step {
        private final Path           parent;
        private final PathAttributes parentAttributes;
        private final Path           child;
        private final PathAttributes childAttributes;
        private final boolean        last;

        Step(Path aParent, PathAttributes aParentAttributes, Path aChild, PathAttributes aChildAttributes, boolean aLast) {
            parent           = aParent;
            parentAttributes = aParentAttributes;
            child            = aChild;
            childAttributes  = aChildAttributes;
            last             = aLast;
        }

        public Path parent() {
            return parent;
        }

        public PathAttributes parentAttributes() {
            return parentAttributes;
        }

        public Path child() {
            return child;
        }

        /** {@code null} when the entry does not exist. */
        public PathAttributes childAttributes() {
            return childAttributes;
        }

        /** The lookup of the final component of the whole path (after all links). */
        public boolean isLast() {
            return last;
        }
    }

    public static final class Walk {
        private final List<Step> steps;
        private final List<Path> directories;
        private final List<PathAttributes> directoryAttributes;
        private final Path       physicalPath;
        private final boolean    finalSymbolicLink;
        private final boolean    exists;

        Walk(List<Step> aSteps, List<Path> aDirectories, List<PathAttributes> aDirectoryAttributes, Path aPhysicalPath, boolean aFinalSymbolicLink, boolean aExists) {
            steps             = List.copyOf(aSteps);
            directories       = List.copyOf(aDirectories);
            directoryAttributes = List.copyOf(aDirectoryAttributes);
            physicalPath      = aPhysicalPath;
            finalSymbolicLink = aFinalSymbolicLink;
            exists            = aExists;
        }

        public List<Step> steps() {
            return steps;
        }

        /** Every existing directory entered on the way, in order, starting with {@code /}. */
        public List<Path> directories() {
            return directories;
        }

        /** Attributes of {@link #directories()}, same order. */
        public List<PathAttributes> directoryAttributes() {
            return directoryAttributes;
        }

        /**
         * Where the path leads: no symbolic links (except a final one that was not followed) and
         * no {@code .} / {@code ..}. A missing tail is appended as written.
         */
        public Path physicalPath() {
            return physicalPath;
        }

        /** The final component is a symbolic link and {@code followFinal} was {@code false}. */
        public boolean isFinalSymbolicLink() {
            return finalSymbolicLink;
        }

        public boolean exists() {
            return exists;
        }
    }

    /**
     * @param aPath        absolute path
     * @param aFollowFinal follow a symbolic link in the final component (a write through it lands
     *                     at its target); {@code false} reports it via {@link Walk#isFinalSymbolicLink()}
     */
    public Walk walk(Path aPath, boolean aFollowFinal) throws IOException {
        if (!aPath.isAbsolute()) {
            throw new IllegalArgumentException("Not an absolute path: " + aPath);
        }

        Path          root        = aPath.getRoot();
        Deque<String> pending     = names(aPath);
        List<Step>    steps       = new ArrayList<>();
        List<Path>    directories = new ArrayList<>();
        List<PathAttributes> directoryAttributes = new ArrayList<>();
        Path          current     = root;
        int           links       = 0;

        directories.add(root);
        directoryAttributes.add(reader.read(root));

        while (!pending.isEmpty()) {
            String name = pending.pollFirst();

            if (".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                // current has no links in it, so its lexical parent is the real parent
                current = current.getParent() == null ? current : current.getParent();
                directories.add(current);
                directoryAttributes.add(reader.read(current));
                continue;
            }

            Path           child            = current.resolve(name);
            PathAttributes parentAttributes = reader.read(current);
            PathAttributes childAttributes  = reader.read(child);
            boolean        last             = pending.isEmpty();

            steps.add(new Step(current, parentAttributes, child, childAttributes, last));

            if (childAttributes == null) {
                Path missing = child;
                for (String rest : pending) {
                    missing = missing.resolve(rest);
                }
                return new Walk(steps, directories, directoryAttributes, missing.normalize(), false, false);
            }

            if (childAttributes.isSymbolicLink()) {
                if (last && !aFollowFinal) {
                    return new Walk(steps, directories, directoryAttributes, child, true, true);
                }
                if (++links > MAX_SYMBOLIC_LINKS) {
                    throw new IOException("Too many symbolic links resolving " + aPath);
                }
                Path target = Files.readSymbolicLink(child);
                Deque<String> targetNames = names(target);
                while (!targetNames.isEmpty()) {
                    pending.addFirst(targetNames.pollLast());
                }
                if (target.isAbsolute()) {
                    current = root;
                    directories.add(root);
                    directoryAttributes.add(reader.read(root));
                }
                continue;
            }

            current = child;
            if (childAttributes.isDirectory()) {
                directories.add(current);
                directoryAttributes.add(childAttributes);
            } else if (!last) {
                throw new IOException("Not a directory: " + current + " (resolving " + aPath + ")");
            }
        }

        return new Walk(steps, directories, directoryAttributes, current, false, true);
    }

    private static Deque<String> names(Path aPath) {
        Deque<String> names = new ArrayDeque<>();
        for (Path name : aPath) {
            names.addLast(name.toString());
        }
        return names;
    }
}
