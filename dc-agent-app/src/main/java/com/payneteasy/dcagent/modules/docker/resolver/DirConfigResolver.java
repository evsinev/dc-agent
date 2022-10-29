package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.volumes.DirConfigVolume;

import java.io.File;

import static com.payneteasy.dcagent.util.CopyDir.copyDir;

public class DirConfigResolver {

    public DirConfigVolume resolve(DirConfigVolume aDirConfig, ResolverContext aContext) {

        File destination = aContext.fullDestination(aDirConfig.getDestination());
        File source      = aContext.fullSource(aDirConfig.getSource(), destination);
        File configDir   = aContext.fullConfig(aDirConfig.getConfigPath());

        copyDir(configDir, source);

        return DirConfigVolume.builder()
                .readonly(aDirConfig.isReadonly())
                .source(source.getAbsolutePath())
                .destination(destination.getAbsolutePath())
                .configPath(aDirConfig.getConfigPath())
                .build();
    }
}
