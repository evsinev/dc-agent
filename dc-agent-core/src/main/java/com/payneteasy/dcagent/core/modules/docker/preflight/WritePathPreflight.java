package com.payneteasy.dcagent.core.modules.docker.preflight;

import com.payneteasy.dcagent.core.config.model.docker.DockerDirectories;
import com.payneteasy.dcagent.core.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.modules.docker.resolver.ResolverContext;
import com.sun.security.auth.module.UnixSystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.payneteasy.dcagent.core.util.Strings.isEmpty;

/**
 * Checks, before any change on the file system, that owner/mode cannot let a container steer a
 * write of the (root) agent. Runs only when some volume declares owner or mode; configs without
 * them keep their old behaviour. The same check runs for DOCKER_CHECK and DOCKER_PUSH.
 *
 * <p>O — directory of a {@code directoryOrCreate} volume with owner/mode (handed to the container);
 * W — every other path the agent writes for this service. Rules:
 * <ol start="0">
 *   <li>O and W are absolute without {@code .} / {@code ..} components;</li>
 *   <li>no W goes through O (checked on every directory the resolution enters, links included);
 *       no {@code dirConfig} root contains O; no two O are one directory; no O goes through
 *       another O or through itself before its last step;</li>
 *   <li>every directory the resolution of O or W enters is owned by root or the agent and not
 *       writable by group/others — a sticky directory is allowed for an entry owned by root or
 *       the agent, except as the direct parent of O;</li>
 *   <li>O is not a symbolic link.</li>
 * </ol>
 */
public class WritePathPreflight {

    private final Map<String, File> serviceWritePaths;
    private final Set<Integer>      trustedUids;
    private final PathWalker        walker;

    /**
     * @param aServiceWritePaths daemontools files and directories the agent writes for the service, by label
     */
    public WritePathPreflight(Map<String, File> aServiceWritePaths) {
        this(aServiceWritePaths, trustedUids((int) new UnixSystem().getUid()), PathAttributes.LSTAT);
    }

    /** root and the agent; the agent usually is root, so the set may have one element. */
    static Set<Integer> trustedUids(int aAgentUid) {
        return aAgentUid == 0 ? Set.of(0) : Set.of(0, aAgentUid);
    }

    WritePathPreflight(Map<String, File> aServiceWritePaths, Set<Integer> aTrustedUids, PathAttributes.Reader aReader) {
        serviceWritePaths = new LinkedHashMap<>(aServiceWritePaths);
        trustedUids       = Set.copyOf(aTrustedUids);
        walker            = new PathWalker(aReader);
    }

    private static final class Target {
        final String  label;
        final File    file;
        final boolean owned;
        final boolean recursiveRoot;

        PathWalker.Walk walk;

        Target(String aLabel, File aFile, boolean aOwned, boolean aRecursiveRoot) {
            label         = aLabel;
            file          = aFile;
            owned         = aOwned;
            recursiveRoot = aRecursiveRoot;
        }

        @Override
        public String toString() {
            return label + " (" + file.getPath() + ")";
        }
    }

    public void check(List<DockerVolume> aVolumes, File aUploadedDir, DockerDirectories aDirectories) {
        if (aVolumes.stream().noneMatch(WritePathPreflight::hasOwnerOrMode)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        List<Target> owned      = new ArrayList<>();
        List<Target> writes     = new ArrayList<>();

        collectTargets(aVolumes, aUploadedDir, aDirectories, owned, writes, violations);

        for (Target target : concat(owned, writes)) {
            if (!checkShape(target, violations)) {
                continue;
            }
            try {
                target.walk = walker.walk(target.file.toPath(), !target.owned);
            } catch (IOException e) {
                violations.add(target + ": cannot resolve the path: " + e.getMessage());
                continue;
            }
            checkTrust(target, violations);
            if (target.owned) {
                checkOwnedDirectory(target, violations);
            }
        }

        checkExclusive(owned, writes, violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException("owner/mode: unsafe paths, nothing was changed:\n  - " + String.join("\n  - ", violations));
        }
    }

    /**
     * Re-checks O right before its owner is changed: rules 0, 2, 3 and the self-traversal of
     * rule 1. Directories created by {@code mkdirs} after {@link #check} get the agent's umask
     * (0777 under umask 000), so a container could swap them before the chown.
     *
     * @return the physical path of O to apply the owner to
     */
    public File verifyOwnedDirectory(String aLabel, File aDir) {
        Target       target     = new Target(aLabel, aDir, true, false);
        List<String> violations = new ArrayList<>();
        if (checkShape(target, violations)) {
            try {
                target.walk = walker.walk(aDir.toPath(), false);
                checkTrust(target, violations);
                checkOwnedDirectory(target, violations);
            } catch (IOException e) {
                violations.add(target + ": cannot resolve the path: " + e.getMessage());
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("owner/mode: unsafe path, the owner was not changed:\n  - " + String.join("\n  - ", violations));
        }
        return target.walk.physicalPath().toFile();
    }

    private static boolean hasOwnerOrMode(DockerVolume aVolume) {
        return aVolume.allVolumes().values().stream().anyMatch(volume -> volume.getOwner() != null || volume.getMode() != null);
    }

    private void collectTargets(List<DockerVolume> aVolumes, File aUploadedDir, DockerDirectories aDirectories, List<Target> aOwned, List<Target> aWrites, List<String> aViolations) {
        if (aDirectories != null && !isEmpty(aDirectories.getSourceBaseDir())) {
            aWrites.add(new Target("directories.sourceBaseDir", new File(aDirectories.getSourceBaseDir()), false, false));
        }

        for (int i = 0; i < aVolumes.size(); i++) {
            DockerVolume element = aVolumes.get(i);
            // The same context VolumesResolver builds: source/destination of getVolume()
            IVolume         contextVolume = element.getVolume();
            ResolverContext context       = new ResolverContext(aDirectories, aUploadedDir, contextVolume.getSource(), contextVolume.getDestination(), null, null, null);
            String          prefix        = "volumes[" + i + "].";

            // The type VolumesResolver.resolveVolume actually runs, in its order
            if (element.getDirConfig() != null) {
                File root = context.fullSource();
                aWrites.add(new Target(prefix + "dirConfig", root, false, true));
                String configPath = element.getDirConfig().getConfigPath();
                if (configPath != null && hasDotDot(configPath)) {
                    // the copy source must stay inside the extracted task
                    aViolations.add(prefix + "dirConfig.configPath (" + configPath + "): '..' is not allowed when owner/mode are used");
                } else {
                    addCopyTargets(prefix + "dirConfig", context.fullConfig(configPath), root, aWrites);
                }
            } else if (element.getFileFetchUrl() != null) {
                aWrites.add(new Target(prefix + "fileFetchUrl", context.fullSource(), false, false));
            } else if (element.getFileConfig() != null) {
                aWrites.add(new Target(prefix + "fileConfig", context.fullSource(), false, false));
            } else if (element.getDirectoryOrCreate() != null) {
                IVolume volume     = element.getDirectoryOrCreate();
                boolean ownerOrMode = volume.getOwner() != null || volume.getMode() != null;
                (ownerOrMode ? aOwned : aWrites).add(new Target(prefix + "directoryOrCreate", context.fullSource(), ownerOrMode, false));
            } else if (element.getLinkToHostDirectory() != null || element.getLinkToHostFile() != null) {
                // the agent does not write to the host for links
                continue;
            } else if (element.getTemplateFileConfig() != null) {
                aWrites.add(new Target(prefix + "templateFileConfig", context.fullSource(), false, false));
            }
        }

        for (Map.Entry<String, File> entry : serviceWritePaths.entrySet()) {
            aWrites.add(new Target(entry.getKey(), entry.getValue(), false, false));
        }
    }

    /**
     * Every destination of the recursive copy of the uploaded config directory. Walks the source
     * the way {@code copyDir} does — {@code File.isDirectory()} follows symbolic links, so this does too.
     */
    private static void addCopyTargets(String aLabel, File aConfigDir, File aRoot, List<Target> aWrites) {
        if (!aConfigDir.isDirectory()) {
            return;
        }
        Path configDir = aConfigDir.toPath();
        try (Stream<Path> files = Files.walk(configDir, FileVisitOption.FOLLOW_LINKS)) {
            files.filter(path -> !path.equals(configDir))
                    .forEach(path -> aWrites.add(new Target(
                            aLabel + " → " + configDir.relativize(path)
                            , new File(aRoot, configDir.relativize(path).toString())
                            , false
                            , false
                    )));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list config dir " + aConfigDir, e);
        }
    }

    private static boolean hasDotDot(String aPath) {
        for (String name : aPath.split("/")) {
            if ("..".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Rule 0. */
    private static boolean checkShape(Target aTarget, List<String> aViolations) {
        String path = aTarget.file.getPath();
        if (!path.startsWith("/")) {
            aViolations.add(aTarget + ": not an absolute path");
            return false;
        }
        for (String name : path.split("/")) {
            if (".".equals(name) || "..".equals(name)) {
                aViolations.add(aTarget + ": '.' and '..' are not allowed in the path");
                return false;
            }
        }
        return true;
    }

    /** Rule 2: the first directory on the way that others than root/agent can change. */
    private void checkTrust(Target aTarget, List<String> aViolations) {
        for (PathWalker.Step step : aTarget.walk.steps()) {
            PathAttributes parent = step.parentAttributes();
            PathAttributes child  = step.childAttributes();

            boolean trustedOwner = trustedUids.contains(parent.uid());
            if (trustedOwner && !parent.isGroupOrOtherWritable()) {
                continue;
            }

            boolean directParentOfOwned = aTarget.owned && step.isLast();
            boolean stickyAllowed       = !directParentOfOwned
                    && child != null
                    && parent.isSticky()
                    && trustedOwner
                    && trustedUids.contains(child.uid());
            if (stickyAllowed) {
                continue;
            }

            aViolations.add(aTarget + ": directory " + step.parent() + " (uid " + parent.uid()
                    + ", mode " + octal(parent.permissions()) + (parent.isSticky() ? " sticky" : "")
                    + ") on the way can be changed by others than root and the agent"
                    + (directParentOfOwned && parent.isSticky() ? "; a sticky directory is not allowed as the parent of an owned volume" : ""));
            return;
        }
    }

    /** Rules 1 (itself) and 3 for O. */
    private static void checkOwnedDirectory(Target aTarget, List<String> aViolations) {
        PathWalker.Walk walk = aTarget.walk;
        if (walk.isFinalSymbolicLink()) {
            aViolations.add(aTarget + ": is a symbolic link; owner/mode need a real directory");
            return;
        }

        List<PathWalker.Step> steps = walk.steps();
        if (steps.isEmpty()) {
            aViolations.add(aTarget + ": the root directory cannot get owner/mode");
            return;
        }
        if (walk.exists() && !steps.get(steps.size() - 1).childAttributes().isDirectory()) {
            aViolations.add(aTarget + ": exists and is not a directory");
            return;
        }

        Path                 physical    = walk.physicalPath();
        List<Path>           directories = walk.directories();
        List<PathAttributes> attributes  = walk.directoryAttributes();
        // an existing O: its own inode earlier on the way means a (bind-mounted) alias of O is an
        // ancestor of O; a directory still to be created cannot be its own ancestor
        PathAttributes       itself      = walk.exists() ? steps.get(steps.size() - 1).childAttributes() : null;
        for (int i = 0; i < directories.size(); i++) {
            boolean finalEntry = i == directories.size() - 1 && directories.get(i).equals(physical);
            if (finalEntry) {
                continue;
            }
            if (directories.get(i).startsWith(physical) || (itself != null && itself.isSameFile(attributes.get(i)))) {
                aViolations.add(aTarget + ": the path goes through the volume directory itself (" + directories.get(i) + ")");
                return;
            }
        }
    }

    /** Rule 1. */
    private static void checkExclusive(List<Target> aOwned, List<Target> aWrites, List<String> aViolations) {
        for (int i = 0; i < aOwned.size(); i++) {
            Target owned = aOwned.get(i);
            if (owned.walk == null) {
                continue;
            }
            Anchor ownedAnchor = Anchor.of(owned.walk);

            for (int j = i + 1; j < aOwned.size(); j++) {
                Target other = aOwned.get(j);
                if (other.walk == null) {
                    continue;
                }
                Anchor otherAnchor = Anchor.of(other.walk);
                if (ownedAnchor.isSameDirectory(otherAnchor)) {
                    aViolations.add(owned + " and " + other + ": the same directory twice with owner/mode");
                } else if (goesThrough(other.walk, ownedAnchor)) {
                    aViolations.add(other + ": goes through " + owned + ", which is handed to the container");
                } else if (goesThrough(owned.walk, otherAnchor)) {
                    aViolations.add(owned + ": goes through " + other + ", which is handed to the container");
                }
            }

            for (Target write : aWrites) {
                if (write.walk == null) {
                    continue;
                }
                if (goesThrough(write.walk, ownedAnchor)) {
                    aViolations.add(write + ": the agent would write inside or through " + owned + ", which is handed to the container");
                } else if (write.recursiveRoot && goesThrough(owned.walk, Anchor.of(write.walk))) {
                    aViolations.add(write + ": the recursive copy contains " + owned + ", which is handed to the container");
                }
            }
        }
    }

    /**
     * A directory identified independently of the path it was reached by: the deepest existing
     * directory on the way (device/inode) plus the missing tail below it. For an existing
     * directory the tail is empty. A bind mount of the directory — or of an ancestor, when the
     * directory is not created yet — has another path but the same anchor.
     */
    private static final class Anchor {
        final Path           path;
        final Path           existing;
        final PathAttributes existingAttributes;
        final Path           tail;

        private Anchor(Path aPath, Path aExisting, PathAttributes aExistingAttributes, Path aTail) {
            path               = aPath;
            existing           = aExisting;
            existingAttributes = aExistingAttributes;
            tail               = aTail;
        }

        static Anchor of(PathWalker.Walk aWalk) {
            List<Path>           directories = aWalk.directories();
            List<PathAttributes> attributes  = aWalk.directoryAttributes();
            Path                 physical    = aWalk.physicalPath();

            int last = directories.size() - 1;
            if (aWalk.exists() && directories.get(last).equals(physical)) {
                return new Anchor(physical, physical, attributes.get(last), physical.getFileSystem().getPath(""));
            }
            Path existing = directories.get(last);
            return new Anchor(physical, existing, attributes.get(last), existing.relativize(physical));
        }

        boolean isSameDirectory(Anchor aOther) {
            return path.equals(aOther.path)
                    || (existingAttributes != null && existingAttributes.isSameFile(aOther.existingAttributes) && tail.equals(aOther.tail));
        }
    }

    /**
     * By path (links resolved) and by device/inode of every directory entered: a walk that enters
     * the anchor directory under any name and continues with the missing tail goes through it.
     */
    private static boolean goesThrough(PathWalker.Walk aWalk, Anchor aAnchor) {
        Path physical = aWalk.physicalPath();
        if (physical.startsWith(aAnchor.path)) {
            return true;
        }
        List<Path>           directories = aWalk.directories();
        List<PathAttributes> attributes  = aWalk.directoryAttributes();
        for (int i = 0; i < directories.size(); i++) {
            Path directory = directories.get(i);
            if (directory.startsWith(aAnchor.path)) {
                return true;
            }
            if (aAnchor.existingAttributes == null || !aAnchor.existingAttributes.isSameFile(attributes.get(i))) {
                continue;
            }
            if (aAnchor.tail.toString().isEmpty()) {
                return true;
            }
            if (physical.startsWith(directory) && directory.relativize(physical).startsWith(aAnchor.tail)) {
                return true;
            }
        }
        return false;
    }

    private static List<Target> concat(List<Target> aFirst, List<Target> aSecond) {
        List<Target> all = new ArrayList<>(aFirst);
        all.addAll(aSecond);
        return all;
    }

    private static String octal(int aPermissions) {
        return String.format("%04o", aPermissions);
    }
}
