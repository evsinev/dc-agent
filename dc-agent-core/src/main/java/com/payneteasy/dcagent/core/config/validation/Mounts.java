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

    record Mount(String device, Path root, Path point) {
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
            mounts.add(new Mount(fields[2], Path.of(unescape(fields[3])), Path.of(unescape(fields[4]))));
        }
        return new Mounts(mounts);
    }

    static String unescape(String aField) {
        return aField.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
    }

    /**
     * The path and every other place a mount of the same file system shows it (a resolved path). The
     * covering mount is the one with the longest mount point above the path, the last of equal ones
     * (stacked on top).
     */
    Set<Path> names(Path aResolved) {
        Set<Path> names = new LinkedHashSet<>();
        names.add(aResolved);
        Mount covering = null;
        for (Mount mount : mounts) {
            if (aResolved.startsWith(mount.point())
                    && (covering == null || mount.point().getNameCount() >= covering.point().getNameCount())) {
                covering = mount;
            }
        }
        if (covering == null) {
            return names;
        }
        Path inside = covering.root().resolve(covering.point().relativize(aResolved));
        for (Mount mount : mounts) {
            if (mount != covering && mount.device().equals(covering.device()) && inside.startsWith(mount.root())) {
                names.add(mount.point().resolve(mount.root().relativize(inside)).normalize());
            }
        }
        return names;
    }
}
