package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.volumes.FileConfigVolume;
import com.payneteasy.dcagent.util.CopyDir;

import java.io.File;

public class FileConfigResolver {

    public FileConfigVolume resolve(FileConfigVolume aUnresolved, ResolverContext aContext) {

        File destination = aContext.fullDestination ();
        File source      = aContext.fullSource      ();
        File configFile  = aContext.fullConfig      ( aUnresolved.getConfigPath() );

        if(!configFile.exists()) {
            throw new IllegalStateException("No config file "
                    + configFile.getAbsolutePath()
                    + "\n in uploaded dir " + aContext.getUploadedDirPath()
                    + "\n destination: " + destination.getAbsolutePath()
                    + "\n source     : " + source.getAbsolutePath()
                    + "\n config     : " + configFile.getAbsolutePath()
            );
        }

        CopyDir.copyFile(configFile, source);

        return FileConfigVolume.builder()
                .readonly    ( aUnresolved.isReadonly()     )
                .source      ( source.getAbsolutePath()     )
                .destination ( destination.getAbsolutePath())
                .configPath  ( aUnresolved.getConfigPath()  )
                .build();

    }
}
