package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.TDocker;

import java.io.File;

public class DockerResolver {

    private final VolumesResolver volumesResolver = new VolumesResolver();

    public TDocker resolve(TDocker aUnresolved, File aUploadedDir) {

        ResolverContext context = new ResolverContext(
                aUnresolved.getHostBaseDir()
                , aUnresolved.getContainerWorkingDir()
                , aUploadedDir);

        return aUnresolved.toBuilder()
                .volumes(volumesResolver.resolveVolumes(
                        aUnresolved.getVolumes()
                        , context)
                )
                .build();
    }
}
