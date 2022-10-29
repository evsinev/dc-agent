package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.volumes.DirectoryOrCreateVolume;

import static com.payneteasy.dcagent.util.SafeFiles.createDirs;

public class DirectoryOrCreateResolver {

    public DirectoryOrCreateVolume resolve(DirectoryOrCreateVolume aUnresolved, ResolverContext aContext) {

        createDirs(aContext.fullSource());

        return DirectoryOrCreateVolume.builder()
                .source       ( aContext.fullSource().getAbsolutePath() )
                .destination  ( aContext.fullDestination().getAbsolutePath() )
                .readonly     ( aUnresolved.isReadonly() )
                .build();
    }
}
