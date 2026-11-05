package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.DockerDirectories;
import com.payneteasy.dcagent.core.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.core.config.model.docker.volumes.DirConfigVolume;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.modules.docker.filesystem.MirrorTarget;
import com.payneteasy.dcagent.core.modules.docker.preflight.PathAttributes;
import com.payneteasy.dcagent.core.modules.docker.preflight.PathWalker;
import com.payneteasy.dcagent.core.modules.docker.preflight.WritePathPreflight;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.payneteasy.dcagent.core.util.Strings.isEmpty;

/**
 * Checks every {@code sync: MIRROR} dirConfig of a task before any change on the file system and
 * turns it into a {@link MirrorTarget}:
 * <ul>
 *   <li>{@code configPath} is a directory inside the extracted task — a typo must not mean "the
 *       task has nothing, delete everything";</li>
 *   <li>{@code source} is neither {@code /}, nor a top-level directory, nor
 *       {@code directories.sourceBaseDir};</li>
 *   <li>no other dirConfig root is the MIRROR root or above it — that copy would put there files
 *       MIRROR then deletes, unseen by CHECK;</li>
 *   <li>no other volume uses the root itself; volumes below it are protected (lexical and physical
 *       paths, file keys);</li>
 *   <li>the root and every directory on its way can be changed only by root and the agent
 *       ({@link WritePathPreflight#checkMirrorRoot}).</li>
 * </ul>
 */
final class MirrorTargets {

    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private final PathWalker walker = new PathWalker(PathAttributes.LSTAT);

    /**
     * One volume's host path in both forms: {@code lexical} normalized as written (only ever adds
     * protection), {@code physical} resolved component by component like the kernel does.
     */
    private record Place(int index, String type, Path lexical, Path physical, boolean exists, List<Path> route) {
    }

    /** @return MIRROR targets by volume index; empty when no volume mirrors */
    Map<Integer, MirrorTarget> prepare(List<DockerVolume> aVolumes, File aUploadedDir, DockerDirectories aDirectories, WritePathPreflight aPreflight) {
        Map<Integer, MirrorTarget> targets = new HashMap<>();
        if (aVolumes.stream().noneMatch(MirrorTargets::isMirror)) {
            return targets;
        }

        // which type runs and whose path is checked must be the same volume for every element:
        // a merged neighbour could copy into the MIRROR root unseen (missing '-' in YAML)
        for (int i = 0; i < aVolumes.size(); i++) {
            Map<String, IVolume> all = aVolumes.get(i).allVolumes();
            if (all.size() > 1) {
                throw new IllegalStateException("volumes[" + i + "]: one list element declares several volume types " + all.keySet()
                        + " (a missing '-' in YAML?); not allowed in a task with sync: MIRROR");
            }
        }

        Place[] places = new Place[aVolumes.size()];
        for (int i = 0; i < aVolumes.size(); i++) {
            places[i] = place(i, aVolumes.get(i), aUploadedDir, aDirectories);
        }

        for (int i = 0; i < aVolumes.size(); i++) {
            if (isMirror(aVolumes.get(i))) {
                targets.put(i, target(aVolumes.get(i).getDirConfig(), places[i], places, aUploadedDir, aDirectories, aPreflight));
            }
        }
        return targets;
    }

    private static boolean isMirror(DockerVolume aVolume) {
        return aVolume.getDirConfig() != null && aVolume.getDirConfig().isMirror();
    }

    private MirrorTarget target(DirConfigVolume aDirConfig, Place aRoot, Place[] aPlaces, File aUploadedDir, DockerDirectories aDirectories, WritePathPreflight aPreflight) {
        String label = "volumes[" + aRoot.index() + "].dirConfig";

        // 'link/..' is the parent of the link's target, not of the link: normalizing it would point
        // the deletion at another directory than the copy
        File source = new ResolverContext(aDirectories, aUploadedDir, aDirConfig.getSource(), aDirConfig.getDestination(), null, null, null).fullSource();
        for (String name : source.getPath().split("/")) {
            if (".".equals(name) || "..".equals(name)) {
                throw new IllegalStateException(label + ".source (" + source.getPath() + "): '.' and '..' are not allowed with sync: MIRROR");
            }
        }

        Path configDir = configDir(aDirConfig, aRoot, aUploadedDir, aDirectories, label);

        for (Path root : List.of(aRoot.lexical(), aRoot.physical())) {
            if (root.getNameCount() < 2) {
                throw new IllegalStateException(label + ".source (" + root + "): sync: MIRROR is not allowed on / or a top-level directory");
            }
        }
        if (aDirectories != null && !isEmpty(aDirectories.getSourceBaseDir())) {
            File base     = new File(aDirectories.getSourceBaseDir());
            Path lexical  = lexical(base);
            Path physical = physical(base.toPath().toAbsolutePath()); // raw: 'link/..' is the parent of the target
            if (lexical.equals(aRoot.lexical()) || physical.equals(aRoot.physical())) {
                throw new IllegalStateException(label + ".source (" + aRoot.lexical() + "): sync: MIRROR is not allowed on directories.sourceBaseDir");
            }
        }

        Set<Path>   protectedPaths = new HashSet<>();
        Set<Path>   routePaths     = new HashSet<>();
        Set<Object> protectedKeys  = new HashSet<>();
        for (Place other : aPlaces) {
            if (other.index() == aRoot.index() || other.lexical() == null) {
                continue;
            }
            boolean sameRoot = other.lexical().equals(aRoot.lexical()) || other.physical().equals(aRoot.physical());
            boolean above    = aRoot.lexical().startsWith(other.lexical()) || aRoot.physical().startsWith(other.physical());
            if ("dirConfig".equals(other.type()) && above) {
                throw new IllegalStateException(label + ": the MIRROR root " + aRoot.lexical() + " is inside the dirConfig of volumes["
                        + other.index() + "] (" + other.lexical() + "); its files would be copied and then deleted");
            }
            if (sameRoot) {
                throw new IllegalStateException(label + ": the MIRROR root " + aRoot.lexical() + " is also used by volumes["
                        + other.index() + "]." + other.type());
            }
            addBelow(protectedPaths, aRoot.lexical(), other.lexical());
            addBelow(protectedPaths, aRoot.physical(), other.physical());
            for (Path step : other.route()) {
                addBelow(routePaths, aRoot.physical(), step);
            }
            if (other.exists() && other.physical().startsWith(aRoot.physical().getRoot())) {
                Object key = fileKey(other.physical());
                if (key != null) {
                    protectedKeys.add(key);
                }
            }
        }

        File physicalRoot = aPreflight.checkMirrorRoot(label + ".source", aRoot.lexical().toFile());

        return new MirrorTarget(
                  label
                , configDir
                , physicalRoot == null ? null : physicalRoot.toPath()
                , KeepPatterns.compile(aDirConfig.getKeep(), label + ".keep")
                , protectedPaths
                , routePaths
                , protectedKeys
        );
    }

    private static Path configDir(DirConfigVolume aDirConfig, Place aRoot, File aUploadedDir, DockerDirectories aDirectories, String aLabel) {
        ResolverContext context  = new ResolverContext(aDirectories, aUploadedDir, aDirConfig.getSource(), aDirConfig.getDestination(), null, null, null);
        Path            uploaded = lexical(aUploadedDir);
        Path            config   = lexical(context.fullConfig(aDirConfig.getConfigPath()));
        String          shown    = aLabel + ".configPath (" + (aDirConfig.getConfigPath() == null ? "default: " + uploaded.relativize(config) : aDirConfig.getConfigPath()) + ")";
        if (!config.startsWith(uploaded)) {
            throw new IllegalStateException(shown + ": must be inside the task");
        }
        PathAttributes attributes;
        try {
            attributes = PathAttributes.LSTAT.read(config);
        } catch (IOException e) {
            throw new IllegalStateException(shown + ": cannot read: " + e.getMessage(), e);
        }
        if (attributes == null || !attributes.isDirectory()) {
            throw new IllegalStateException(shown + ": no such directory in the task; with sync: MIRROR that would delete everything under "
                    + aRoot.lexical());
        }
        return config;
    }

    private Place place(int aIndex, DockerVolume aVolume, File aUploadedDir, DockerDirectories aDirectories) {
        IVolume volume = aVolume.getVolume();
        String  type   = aVolume.volumeType();
        if (isEmpty(volume.getSource()) && isEmpty(volume.getDestination())) {
            return new Place(aIndex, type, null, null, false, List.of());
        }
        File source = new ResolverContext(aDirectories, aUploadedDir, volume.getSource(), volume.getDestination(), null, null, null).fullSource();
        Path lexical = lexical(source);
        try {
            // the raw path, not the normalized one: 'link/..' is the parent of the link's target
            PathWalker.Walk walk = walker.walk(source.toPath().toAbsolutePath(), true);
            List<Path> route = new java.util.ArrayList<>();
            for (PathWalker.Step step : walk.steps()) {
                if (step.childAttributes() != null) {
                    route.add(step.child());
                }
            }
            return new Place(aIndex, type, lexical, walk.physicalPath(), walk.exists(), route);
        } catch (IOException e) {
            throw new IllegalStateException("volumes[" + aIndex + "]." + type + ".source (" + lexical + "): cannot resolve: " + e.getMessage(), e);
        }
    }

    /** @param aPath absolute, not normalized */
    private Path physical(Path aPath) {
        try {
            return walker.walk(aPath, true).physicalPath();
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve " + aPath + ": " + e.getMessage(), e);
        }
    }

    private static Path lexical(File aFile) {
        return aFile.toPath().toAbsolutePath().normalize();
    }

    private static void addBelow(Set<Path> aPaths, Path aRoot, Path aOther) {
        if (aOther.startsWith(aRoot) && !aOther.equals(aRoot)) {
            aPaths.add(aRoot.relativize(aOther));
        }
    }

    private static Object fileKey(Path aPath) {
        try {
            return Files.readAttributes(aPath, BasicFileAttributes.class, NOFOLLOW).fileKey();
        } catch (IOException e) {
            return null;
        }
    }
}
