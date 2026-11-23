package com.payneteasy.dcagent.core.config.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The mounts of this process ({@code /proc/self/mountinfo}) and the other names a path has through
 * them. A bind mount shows a directory of a file system at a second place: {@code mount --bind
 * /etc/service.d/db/app /srv/stage} makes {@code /srv/stage} the same directory as
 * {@code /etc/service.d/db/app}. Every mount line names the device, the directory of that file system
 * it shows (its root) and where — so a path maps to (device, path inside the file system), and back to
 * every place another mount of the same device shows it.
 */
final class Mounts {

    record Mount(String id, String parent, String device, Path root, Path point) {
    }

    private final List<Mount> mounts;

    Mounts(List<Mount> aMounts) {
        mounts = List.copyOf(aMounts);
    }

    /** This process's mounts; none when not Linux. */
    static Mounts read() throws IOException {
        Path mountinfo = Path.of("/proc/self/mountinfo");
        if (!Files.isReadable(mountinfo)) {
            return new Mounts(List.of());
        }
        return parse(Files.readAllLines(mountinfo));
    }

    /** {@code id parent major:minor root point options ...} */
    static Mounts parse(List<String> aLines) throws IOException {
        List<Mount> mounts = new ArrayList<>();
        for (String line : aLines) {
            String[] fields = line.split(" ");
            if (fields.length < 5) {
                throw new IOException("unexpected mountinfo line");
            }
            mounts.add(new Mount(fields[0], fields[1], fields[2], Path.of(unescape(fields[3])), Path.of(unescape(fields[4]))));
        }
        return new Mounts(mounts);
    }

    static String unescape(String aField) {
        return aField.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
    }

    /**
     * The path and every other place a mount of the same file system shows it (a resolved path). Only
     * visible mounts count: a mount covered by a later one on or above its point stays in mountinfo, but
     * no name reaches it.
     *
     * @throws IOException the mounts have no root mount to start from
     */
    Set<Path> names(Path aResolved) throws IOException {
        Set<Path> names = new LinkedHashSet<>();
        names.add(aResolved);
        if (mounts.isEmpty()) {
            return names;
        }
        Mount covering = covering(aResolved);
        Path  inside   = covering.root().resolve(covering.point().relativize(aResolved));
        for (Mount mount : mounts) {
            if (mount != covering && mount.device().equals(covering.device()) && inside.startsWith(mount.root())) {
                Path name = mount.point().resolve(mount.root().relativize(inside)).normalize();
                if (covering(name) == mount) { // that name really reaches it
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * The mount a path lands on, as the kernel walks it: from the root mount, at every component the mount
     * on that point whose parent is the current one (repeatedly: stacked mounts), the last of equal ones.
     */
    Mount covering(Path aResolved) throws IOException {
        Set<String> ids = new java.util.HashSet<>();
        for (Mount mount : mounts) {
            ids.add(mount.id());
        }
        Mount current = null;
        for (Mount mount : mounts) {
            if (!ids.contains(mount.parent()) && mount.point().getNameCount() == 0) {
                current = mount;
            }
        }
        if (current == null) {
            throw new IOException("no root mount");
        }
        Path point = aResolved.getRoot();
        current = descend(current, point);
        for (Path name : aResolved) {
            point   = point.resolve(name);
            current = descend(current, point);
        }
        return current;
    }

    private Mount descend(Mount aCurrent, Path aPoint) {
        Mount current = aCurrent;
        for (boolean moved = true; moved; ) {
            moved = false;
            Mount top = null;
            for (Mount mount : mounts) {
                if (mount.parent().equals(current.id()) && mount.point().equals(aPoint) && mount != current) {
                    top = mount;
                }
            }
            if (top != null) {
                current = top;
                moved   = true;
            }
        }
        return current;
    }
}
