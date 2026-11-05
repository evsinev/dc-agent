package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.modules.docker.resolver.KeepPatterns;

import java.nio.file.Path;
import java.util.Set;

/**
 * A {@code dirConfig} with {@code sync: MIRROR}, checked before any change on the file system.
 *
 * @param label          config path for messages, e.g. {@code volumes[0].dirConfig}
 * @param configDir      the directory in the extracted task (exists, inside the task)
 * @param physicalRoot   the host directory without symbolic links (its way is trusted), or
 *                       {@code null} when it did not exist at the check — then everything in it
 *                       was created by this push and nothing is deleted
 * @param keep           what is never deleted
 * @param protectedPaths paths relative to the root that belong to other volumes of the task:
 *                       never deleted, nor their parents
 * @param routePaths     paths relative to the root that the resolution of another volume's path
 *                       goes through (directories and symbolic links): not deleted themselves,
 *                       their content is
 * @param protectedKeys  {@link java.nio.file.attribute.BasicFileAttributes#fileKey()} of the
 *                       existing ones — the same object under another name (bind mount, hard link)
 *                       is protected too
 */
public record MirrorTarget(
          String       label
        , Path         configDir
        , Path         physicalRoot
        , KeepPatterns keep
        , Set<Path>    protectedPaths
        , Set<Path>    routePaths
        , Set<Object>  protectedKeys
) {
    public MirrorTarget {
        protectedPaths = Set.copyOf(protectedPaths);
        routePaths     = Set.copyOf(routePaths);
        protectedKeys  = Set.copyOf(protectedKeys);
    }
}
