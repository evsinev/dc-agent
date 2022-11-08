package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;

import java.io.File;

public class DockerResolver {

    private final VolumesResolver volumesResolver = new VolumesResolver();

    public TDocker resolve(TDocker aUnresolved, File aUploadedDir, IFileSystem aFilesystem, IActionLogger aLogger) {

        return aUnresolved.toBuilder()
                .volumes(volumesResolver.resolveVolumes(
                          aUnresolved.getVolumes()
                        , aUploadedDir
                        , aUnresolved.getDirectories()
                        , aFilesystem
                        , aLogger
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
