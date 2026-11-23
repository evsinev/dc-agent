package com.payneteasy.dcagent.core.config.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * {@code realpath -m} that remembers the way: an absolute path is resolved component by component;
 * every symbolic link met on the way is recorded (where the link itself lies — its resolved parent
 * plus its name), and a missing tail is kept as written instead of being dropped. Protecting only
 * the final target is not enough: a command writing at a link's place replaces the link, and the
 * service that reads through it gets the new file.
 */
public final class PathResolver {

    /** As the Linux kernel ({@code MAXSYMLINKS}). */
    static final int MAX_LINKS = 40;

    private PathResolver() {
    }

    /**
     * @param aPath  an absolute path
     * @param aLinks receives the place of every link followed (resolved parent + link name)
     * @return the resolved path; a missing part is appended as written
     * @throws IOException more than {@link #MAX_LINKS} links (a loop included), or a component that
     *                     cannot be inspected
     */
    public static Path resolve(Path aPath, List<Path> aLinks) throws IOException {
        if (!aPath.isAbsolute()) {
            throw new IllegalArgumentException("not an absolute path");
        }
        Deque<String> pending = new ArrayDeque<>();
        for (Path name : aPath) {
            pending.addLast(name.toString());
        }

        Path    current      = aPath.getRoot();
        boolean currentIsDir = true;
        int     links        = 0;
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (name.isEmpty() || ".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                Path parent = current.getParent();
                current = parent != null ? parent : current;
                // current is resolved (no links up to a missing part): a plain look decides
                currentIsDir = Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS);
                continue;
            }
            Path next = current.resolve(name);
            if (!currentIsDir) {
                current = next; // below a missing path or a file: kept as written
                continue;
            }
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(next, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                current      = next; // the missing tail is kept as written
                currentIsDir = false;
                continue;
            }
            if (!attributes.isSymbolicLink()) {
                current      = next;
                currentIsDir = attributes.isDirectory();
                continue;
            }
            if (++links > MAX_LINKS) {
                throw new IOException("more than " + MAX_LINKS + " symbolic links (a loop?)");
            }
            aLinks.add(next);
            Path target = Files.readSymbolicLink(next);
            if (target.isAbsolute()) {
                current = target.getRoot();
            }
            currentIsDir = true; // current is the link's directory (or /), which exists
            List<String> names = new java.util.ArrayList<>();
            for (Path part : target) {
                names.add(part.toString());
            }
            for (int i = names.size() - 1; i >= 0; i--) {
                pending.addFirst(names.get(i));
            }
        }
        return current;
    }
}
