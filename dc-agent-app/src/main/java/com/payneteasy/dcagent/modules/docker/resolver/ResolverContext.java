package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.util.Strings;

import java.io.File;


public class ResolverContext {

    private final String hostBaseDir;
    private final String containerWorkingDir;
    private final File   uploadedPath;
    private final String source;
    private final String destination;

    public ResolverContext(String hostBaseDir, String containerWorkingDir, File uploadedPath, String source, String destination) {
        this.hostBaseDir         = hostBaseDir;
        this.containerWorkingDir = containerWorkingDir;
        this.uploadedPath        = uploadedPath;
        this.source              = source;
        this.destination         = destination;
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

        return new File(hostBaseDir, source);
    }

    public File fullConfig(String aConfigDirOrFile) {
        if(Strings.hasText(aConfigDirOrFile)) {
            return new File(uploadedPath, aConfigDirOrFile);
        }

        return new File(uploadedPath, fullSource().getName());
    }

    public String getUploadedDirPath() {
        return uploadedPath.getAbsolutePath();
    }
}
