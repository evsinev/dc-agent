package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.DockerVolume;

import java.util.List;
import java.util.stream.Collectors;

public class VolumesResolver {

    private final DirConfigResolver    dirConfigResolver    = new DirConfigResolver();
    private final FileFetchUrlResolver fileFetchUrlResolver = new FileFetchUrlResolver();

    public List<DockerVolume> resolveVolumes(List<DockerVolume> volumes, ResolverContext aContext) {
        return volumes.stream()
                .map(dockerVolume -> resolveVolume(dockerVolume, aContext))
                .collect(Collectors.toList());
    }

    private DockerVolume resolveVolume(DockerVolume aUnresolved, ResolverContext aContext) {
        if (aUnresolved.getDirConfig() != null) {
            return DockerVolume.builder()
                    .dirConfig(dirConfigResolver.resolve(aUnresolved.getDirConfig(), aContext))
                    .build();
        } else if (aUnresolved.getFileFetchUrl() != null) {
            return DockerVolume.builder()
                    .fileFetchUrl(fileFetchUrlResolver.resolve(aUnresolved.getFileFetchUrl(), aContext))
                    .build();
        }
        throw new IllegalStateException("Not supported " + aUnresolved);
    }
}
