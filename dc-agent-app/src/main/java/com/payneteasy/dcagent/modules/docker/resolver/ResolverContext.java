package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.DockerDirectories;
import com.payneteasy.dcagent.modules.docker.IActionLogger;
import com.payneteasy.dcagent.modules.docker.filesystem.IFileSystem;

import java.io.File;

import static com.payneteasy.dcagent.util.Strings.hasText;
import static com.payneteasy.dcagent.util.Strings.isEmpty;


public class ResolverContext {

    private final DockerDirectories directories;
    private final File              uploadedPath;
    private final String            source;
    private final String            destination;
    private final IFileSystem       fileSystem;
    private final IActionLogger     logger;

    public ResolverContext(DockerDirectories directories, File uploadedPath, String source, String destination, IFileSystem fileSystem, IActionLogger logger) {
        this.directories  = directories;
        this.uploadedPath = uploadedPath;
        this.source       = source;
        this.destination  = destination;
        this.fileSystem   = fileSystem;
        this.logger       = logger;
    }

    public File fullDestination() {
        String path = destination != null ? destination : source;

        if (path.startsWith("/")) {
            return new File(path);
        }

        return new File(getDestinationBaseDir(), path);
    }

    private File getDestinationBaseDir() {
        if(directories == null) {
            throw new IllegalStateException("No directories field in the config");
        }

        if(isEmpty(directories.getDestinationBaseDir())) {
            throw new IllegalStateException("directories.destinationBaseDir is empty");
        }
        return new File(directories.getDestinationBaseDir());
    }

    private File getSourceBaseDir() {
        if(directories == null) {
            throw new IllegalStateException("Trying to calculate source dir but no any directories field in the config");
        }

        if(isEmpty(directories.getSourceBaseDir())) {
            throw new IllegalStateException("directories.sourceBaseDir is empty");
        }
        return new File(directories.getSourceBaseDir());
    }

    public File fullSource() {
        if (isEmpty(source)) {
            return fullDestination();
        }

        if (source.startsWith("/")) {
            return new File(source);
        }

        if(source.startsWith("./")) {
            return new File(getSourceBaseDir(), source.substring(2));
        }
        
        return new File(getSourceBaseDir(), source);
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

    public IActionLogger getLogger() {
        return logger;
    }
}
