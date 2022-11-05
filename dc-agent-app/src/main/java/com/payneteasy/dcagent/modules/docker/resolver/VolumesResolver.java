package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.DockerDirectories;
import com.payneteasy.dcagent.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.modules.docker.filesystem.FileSystemWriterImpl;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

public class VolumesResolver {

    private final DirConfigResolver           dirConfigResolver           = new DirConfigResolver();
    private final FileConfigResolver          fileConfigResolver          = new FileConfigResolver();
    private final FileFetchUrlResolver        fileFetchUrlResolver        = new FileFetchUrlResolver();
    private final DirectoryOrCreateResolver   directoryOrCreateResolver   = new DirectoryOrCreateResolver();
    private final LinkToHostDirectoryResolver linkToHostDirectoryResolver = new LinkToHostDirectoryResolver();
    private final LinkToHostFileResolver      linkToHostFileResolver      = new LinkToHostFileResolver();

    public List<DockerVolume> resolveVolumes(
            List<DockerVolume> volumes
            , File uploadedPath
            , DockerDirectories aDirectories
    ) {
        return volumes.stream()
                .map(dockerVolume -> resolveVolume(dockerVolume, new ResolverContext(
                        aDirectories
                        , uploadedPath
                        , dockerVolume.getVolume().getSource()
                        , dockerVolume.getVolume().getDestination()
                        , new FileSystemWriterImpl()
                )))
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
        } else if (aUnresolved.getFileConfig() != null) {
            return DockerVolume.builder()
                    .fileConfig(fileConfigResolver.resolve(aUnresolved.getFileConfig(), aContext))
                    .build();
        } else if (aUnresolved.getDirectoryOrCreate() != null) {
            return DockerVolume.builder()
                    .directoryOrCreate(directoryOrCreateResolver.resolve(aUnresolved.getDirectoryOrCreate(), aContext))
                    .build();
        } else if (aUnresolved.getLinkToHostDirectory() != null) {
            return DockerVolume.builder()
                    .linkToHostDirectory(linkToHostDirectoryResolver.resolve(aUnresolved.getLinkToHostDirectory(), aContext))
                    .build();
        } else if(aUnresolved.getLinkToHostFile() != null) {
            return DockerVolume.builder()
                    .linkToHostFile(linkToHostFileResolver.resolve(aUnresolved.getLinkToHostFile(), aContext))
                    .build();
        } else {
            throw new IllegalStateException("Not supported " + aUnresolved);
        }
    }
}