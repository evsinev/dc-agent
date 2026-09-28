package com.payneteasy.dcagent.core.config.model.docker;

import com.payneteasy.dcagent.core.config.model.docker.volumes.*;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * Every volume type set in this list element, by its dc-docker.yml name. Normally one; a
     * missing dash in YAML can merge two types into one element.
     */
    public Map<String, IVolume> allVolumes() {
        Map<String, IVolume> all = new LinkedHashMap<>();
        putIfSet(all, "directoryOrCreate"  , directoryOrCreate);
        putIfSet(all, "fileConfig"         , fileConfig);
        putIfSet(all, "dirConfig"          , dirConfig);
        putIfSet(all, "fileFetchUrl"       , fileFetchUrl);
        putIfSet(all, "linkToHostDirectory", linkToHostDirectory);
        putIfSet(all, "linkToHostFile"     , linkToHostFile);
        putIfSet(all, "templateFileConfig" , templateFileConfig);
        return all;
    }

    private static void putIfSet(Map<String, IVolume> aMap, String aType, IVolume aVolume) {
        if (aVolume != null) {
            aMap.put(aType, aVolume);
        }
    }

    /** Name of the volume type as written in dc-docker.yml. */
    public String volumeType() {
        if(directoryOrCreate != null) {
            return "directoryOrCreate";
        } else if(fileConfig != null) {
            return "fileConfig";
        } else if(dirConfig != null) {
            return "dirConfig";
        } else if(fileFetchUrl != null) {
            return "fileFetchUrl";
        } else if(linkToHostDirectory != null) {
            return "linkToHostDirectory";
        } else if(linkToHostFile != null) {
            return "linkToHostFile";
        } else if(templateFileConfig != null) {
            return "templateFileConfig";
        } else {
            throw new IllegalStateException("No any config for volume " + this);
        }
    }

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
