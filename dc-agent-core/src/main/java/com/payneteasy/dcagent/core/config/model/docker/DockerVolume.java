package com.payneteasy.dcagent.core.config.model.docker;

import com.payneteasy.dcagent.core.config.model.docker.volumes.*;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class DockerVolume {
    DirectoryOrCreateVolume   directoryOrCreate;
    FileConfigVolume          fileConfig;
    DirConfigVolume           dirConfig;
    FileFetchUrlVolume        fileFetchUrl;
    LinkToHostDirectoryVolume linkToHostDirectory;
    LinkToHostFileVolume      linkToHostFile;
    TemplateFileConfigVolume  templateFileConfig;

    public IVolume getVolume() {
        if(directoryOrCreate != null) {
            return directoryOrCreate;
        } else if(fileConfig != null) {
            return fileConfig;
        } else if(dirConfig != null) {
            return dirConfig;
        } else if(fileFetchUrl != null) {
            return fileFetchUrl;
        } else if(linkToHostDirectory != null) {
            return linkToHostDirectory;
        } else if(linkToHostFile != null) {
            return linkToHostFile;
        } else if(templateFileConfig != null) {
            return templateFileConfig;
        } else {
            throw new IllegalStateException("No any config for volume " + this);
        }
    }
}
