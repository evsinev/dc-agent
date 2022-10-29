package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.util.Strings;

import java.io.File;


public class ResolverContext {

    private final String hostBaseDir;
    private final String containerWorkingDir;
    private final File   uploadedPath;

    public ResolverContext(String hostBaseDir, String containerWorkingDir, File uploadedPath) {
        this.hostBaseDir         = hostBaseDir;
        this.containerWorkingDir = containerWorkingDir;
        this.uploadedPath        = uploadedPath;
    }

    public File fullDestination(String aDestination) {
        if (aDestination.startsWith("/")) {
            return new File(aDestination);
        }

        return new File(containerWorkingDir, aDestination);
    }

    public File fullSource(String aSource, File aDestination) {
        if (Strings.isEmpty(aSource)) {
            return aDestination;
        }

        if (aSource.startsWith("/")) {
            return new File(aSource);
        }

        return new File(hostBaseDir, aSource);
    }

    public File fullConfig(String aConfigDir) {
        return new File(uploadedPath, aConfigDir);
    }
}
