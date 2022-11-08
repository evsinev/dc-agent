package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.volumes.DirectoryOrCreateVolume;

public class DirectoryOrCreateResolver {

    public DirectoryOrCreateVolume resolve(DirectoryOrCreateVolume aUnresolved, ResolverContext aContext) {

        aContext.fileSystem().createDirectories(null, aContext.fullSource());

        return DirectoryOrCreateVolume.builder()
                .source       ( aContext.fullSource().getAbsolutePath() )
                .destination  ( aContext.fullDestination().getAbsolutePath() )
                .readonly     ( aUnresolved.isReadonly() )
                .build();
    }
}
