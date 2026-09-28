package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.config.model.docker.volumes.DirectoryOrCreateVolume;
import com.payneteasy.dcagent.core.modules.docker.preflight.WritePathPreflight;

import java.io.File;
import java.util.Map;

public class DirectoryOrCreateResolver {

    private final VolumeOwnerResolver ownerResolver = new VolumeOwnerResolver();

    public DirectoryOrCreateVolume resolve(DirectoryOrCreateVolume aUnresolved, ResolverContext aContext) {

        TVolumeOwner owner = ownerResolver.resolve(aUnresolved.getOwner(), aContext.securityContext(), aContext.volumePath() + ".owner");

        aContext.fileSystem().createDirectories(null, aContext.fullSource());

        if (owner != null || aUnresolved.getMode() != null) {
            // Again after mkdirs (new directories get the agent's umask); the physical path of a
            // chain only root/agent can change
            File physical = new WritePathPreflight(Map.of()).verifyOwnedDirectory(aContext.volumePath(), aContext.fullSource());
            aContext.fileSystem().applyOwner(physical, owner, aUnresolved.getMode());
        }

        return DirectoryOrCreateVolume.builder()
                .source       ( aContext.fullSource().getAbsolutePath() )
                .destination  ( aContext.fullDestination().getAbsolutePath() )
                .readonly     ( aUnresolved.isReadonly() )
                .owner        ( owner )
                .mode         ( aUnresolved.getMode() )
                .build();
    }
}
