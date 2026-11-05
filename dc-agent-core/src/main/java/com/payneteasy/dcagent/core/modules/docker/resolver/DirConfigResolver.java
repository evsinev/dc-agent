package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.volumes.DirConfigVolume;
import com.payneteasy.dcagent.core.modules.docker.filesystem.MirrorTarget;

import java.io.File;

public class DirConfigResolver {

    /** @param aMirror the checked MIRROR target, {@code null} for OVERLAY */
    public DirConfigVolume resolve(DirConfigVolume aDirConfig, MirrorTarget aMirror, ResolverContext aContext) {

        File destination = aContext.fullDestination();
        File source      = aContext.fullSource();
        File configDir   = aContext.fullConfig(aDirConfig.getConfigPath());

        if (aMirror != null) {
            aContext.fileSystem().mirrorDir(aMirror, source);
        } else if (aDirConfig.isMirror()) {
            throw new IllegalStateException(aContext.volumePath() + ": sync: MIRROR was not checked before the change");
        } else {
            aContext.fileSystem().copyDir(null, configDir, source);
        }

        return DirConfigVolume.builder()
                .readonly(aDirConfig.isReadonly())
                .source(source.getAbsolutePath())
                .destination(destination.getAbsolutePath())
                .configPath(aDirConfig.getConfigPath())
                .sync(aDirConfig.getSync())
                .keep(aDirConfig.getKeep())
                .build();
    }
}
