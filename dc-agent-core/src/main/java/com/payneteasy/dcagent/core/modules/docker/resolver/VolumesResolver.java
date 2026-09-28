package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import com.payneteasy.dcagent.core.config.model.docker.DockerDirectories;
import com.payneteasy.dcagent.core.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;
import com.payneteasy.dcagent.core.modules.docker.preflight.WritePathPreflight;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.payneteasy.dcagent.core.util.Strings.isEmpty;

public class VolumesResolver {

    private final DirConfigResolver           dirConfigResolver           = new DirConfigResolver();
    private final FileConfigResolver          fileConfigResolver          = new FileConfigResolver();
    private final FileFetchUrlResolver        fileFetchUrlResolver        = new FileFetchUrlResolver();
    private final DirectoryOrCreateResolver   directoryOrCreateResolver   = new DirectoryOrCreateResolver();
    private final LinkToHostDirectoryResolver linkToHostDirectoryResolver = new LinkToHostDirectoryResolver();
    private final LinkToHostFileResolver      linkToHostFileResolver      = new LinkToHostFileResolver();
    private final TemplateFileConfigResolver  templateFileConfigResolver  = new TemplateFileConfigResolver();
    private final VolumeOwnerResolver         ownerResolver               = new VolumeOwnerResolver();

    public List<DockerVolume> resolveVolumes(
            List<DockerVolume> volumes
            , File uploadedPath
            , DockerDirectories aDirectories
            , IFileSystem aFilesystem
            , IActionLogger aLogger,
            List<BoundVariable> aBoundVariables) {
        return resolveVolumes(volumes, uploadedPath, aDirectories, aFilesystem, aLogger, aBoundVariables, null, null);
    }

    public List<DockerVolume> resolveVolumes(
            List<DockerVolume> volumes
            , File uploadedPath
            , DockerDirectories aDirectories
            , IFileSystem aFilesystem
            , IActionLogger aLogger
            , List<BoundVariable> aBoundVariables
            , TSecurityContext aSecurityContext
            , WritePathPreflight aPreflight) {

        // All config checks before the first change on the file system
        for (int i = 0; i < volumes.size(); i++) {
            checkOwnerAndMode(volumes.get(i), volumePath(i, volumes.get(i)), aSecurityContext, aLogger);
        }

        if (aPreflight != null) {
            aPreflight.check(volumes, uploadedPath, aDirectories);
        }

        createSourceBaseDir(aDirectories, aFilesystem);

        List<DockerVolume> resolved = new ArrayList<>(volumes.size());
        for (int i = 0; i < volumes.size(); i++) {
            DockerVolume dockerVolume = volumes.get(i);
            resolved.add(resolveVolume(dockerVolume, new ResolverContext(
                    aDirectories
                    , uploadedPath
                    , dockerVolume.getVolume().getSource()
                    , dockerVolume.getVolume().getDestination()
                    , aFilesystem
                    , aLogger
                    , aBoundVariables
                    , aSecurityContext
                    , volumePath(i, dockerVolume)
            )));
        }
        return List.copyOf(resolved);
    }

    private static String volumePath(int aIndex, DockerVolume aVolume) {
        return "volumes[" + aIndex + "]." + aVolume.volumeType();
    }

    /**
     * owner/mode are declared on every volume type so that Gson keeps them; only
     * directoryOrCreate supports them (the agent creates that directory itself).
     */
    private void checkOwnerAndMode(DockerVolume aVolume, String aPath, TSecurityContext aSecurityContext, IActionLogger aLogger) {
        Map<String, IVolume> all = aVolume.allVolumes();
        boolean withOwnerOrMode = all.values().stream().anyMatch(volume -> volume.getOwner() != null || volume.getMode() != null);
        if (!withOwnerOrMode) {
            return;
        }

        // Which type runs and which one is checked must be the same volume
        if (all.size() > 1) {
            throw new IllegalStateException(aPath + ": one list element declares several volume types " + all.keySet()
                    + " (a missing '-' in YAML?); owner and mode need exactly one");
        }

        IVolume volume = aVolume.getVolume();
        if (aVolume.getDirectoryOrCreate() == null) {
            throw new IllegalStateException(aPath + ": owner and mode are supported only by directoryOrCreate");
        }

        if (volume.getMode() != null && !VolumeMode.isValid(volume.getMode())) {
            throw new IllegalStateException(aPath + ".mode: expected three octal digits with an optional leading zero (e.g. \"0770\"), got '" + volume.getMode() + "'");
        }

        ownerResolver.resolve(volume.getOwner(), aSecurityContext, aPath + ".owner");

        if (volume.isReadonly()) {
            aLogger.info("\u26A0\uFE0F  {}: owner/mode on a readonly volume — the container cannot write there anyway", aPath); // ⚠️
        }
    }

    private void createSourceBaseDir(DockerDirectories aDirectories, IFileSystem aFilesystem) {
        if(aDirectories == null || isEmpty(aDirectories.getSourceBaseDir())) {
            return;
        }
        aFilesystem.createDirectories(null, new File(aDirectories.getSourceBaseDir()));
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
        } else if(aUnresolved.getTemplateFileConfig() != null) {
            return DockerVolume.builder()
                    .templateFileConfig(templateFileConfigResolver.resolve(aUnresolved.getTemplateFileConfig(), aContext))
                    .build();
        } else {
            throw new IllegalStateException("Not supported " + aUnresolved);
        }
    }
}
