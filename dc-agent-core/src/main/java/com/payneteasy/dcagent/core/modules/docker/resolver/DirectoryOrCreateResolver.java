package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.config.model.docker.volumes.DirectoryOrCreateVolume;

public class DirectoryOrCreateResolver {

    private final VolumeOwnerResolver ownerResolver = new VolumeOwnerResolver();

    public DirectoryOrCreateVolume resolve(DirectoryOrCreateVolume aUnresolved, ResolverContext aContext) {

        TVolumeOwner owner = ownerResolver.resolve(aUnresolved.getOwner(), aContext.securityContext(), aContext.volumePath() + ".owner");

        aContext.fileSystem().createDirectories(null, aContext.fullSource());

        return DirectoryOrCreateVolume.builder()
                .source       ( aContext.fullSource().getAbsolutePath() )
                .destination  ( aContext.fullDestination().getAbsolutePath() )
                .readonly     ( aUnresolved.isReadonly() )
                .owner        ( owner )
                .mode         ( aUnresolved.getMode() )
                .build();
    }
}
