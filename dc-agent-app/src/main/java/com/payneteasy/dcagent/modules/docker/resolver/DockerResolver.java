package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.Owner;
import com.payneteasy.dcagent.config.model.docker.TDocker;

import java.io.File;

public class DockerResolver {

    private final VolumesResolver volumesResolver = new VolumesResolver();

    public TDocker resolve(TDocker aUnresolved, File aUploadedDir) {

        return aUnresolved.toBuilder()
                .volumes(volumesResolver.resolveVolumes(
                          aUnresolved.getVolumes()
                        , aUnresolved.getHostBaseDir()
                        , aUnresolved.getContainerWorkingDir()
                        , aUploadedDir
                        )
                )
                .owner(resolveOwner(aUnresolved.getOwner()))
                .build();
    }

    private Owner resolveOwner(Owner owner) {
        if(owner != null) {
            return owner;
        }

        return Owner.builder()
                .user("root")
                .group("root")
                .build();

    }
}
