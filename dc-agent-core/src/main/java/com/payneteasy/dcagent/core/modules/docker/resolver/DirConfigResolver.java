package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.volumes.DirConfigVolume;

import java.io.File;

public class DirConfigResolver {

    public DirConfigVolume resolve(DirConfigVolume aDirConfig, ResolverContext aContext) {

        File destination = aContext.fullDestination();
        File source      = aContext.fullSource();
        File configDir   = aContext.fullConfig(aDirConfig.getConfigPath());

        if (aDirConfig.isMirror()) {
            throw new UnsupportedOperationException("sync: MIRROR is not implemented yet");
        }

        aContext.fileSystem().copyDir(null, configDir, source);

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
