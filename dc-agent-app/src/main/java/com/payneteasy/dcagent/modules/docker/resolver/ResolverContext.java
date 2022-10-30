package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.modules.docker.filesystem.IFileSystem;
import com.payneteasy.dcagent.util.Strings;

import java.io.File;

import static com.payneteasy.dcagent.util.Strings.hasText;


public class ResolverContext {

    private final String      hostBaseDir;
    private final String      containerWorkingDir;
    private final File        uploadedPath;
    private final String      source;
    private final String      destination;
    private final IFileSystem fileSystem;

    public ResolverContext(String hostBaseDir, String containerWorkingDir, File uploadedPath, String source, String destination, IFileSystem fileSystem) {
        this.hostBaseDir         = hostBaseDir;
        this.containerWorkingDir = containerWorkingDir;
        this.uploadedPath        = uploadedPath;
        this.source              = source;
        this.destination         = destination;
        this.fileSystem          = fileSystem;
    }

    public File fullDestination() {
        String path = destination != null ? destination : source;

        if (path.startsWith("/")) {
            return new File(path);
        }

        return new File(containerWorkingDir, path);
    }

    public File fullSource() {
        if (Strings.isEmpty(source)) {
            return fullDestination();
        }

        if (source.startsWith("/")) {
            return new File(source);
        }

        if(source.startsWith("./")) {
            return new File(hostBaseDir, source.substring(2));
        }
        
        return new File(hostBaseDir, source);
    }

    public File fullConfig(String aConfigDirOrFile) {
        if(hasText(aConfigDirOrFile)) {
            return new File(uploadedPath, aConfigDirOrFile);
        }

        return new File(uploadedPath, fullSource().getName());
    }

    public String getUploadedDirPath() {
        return uploadedPath.getAbsolutePath();
    }

    public IFileSystem fileSystem() {
        return fileSystem;
    }
}
