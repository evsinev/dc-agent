package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.volumes.FileConfigVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class FileConfigResolver {

    private static final Logger LOG = LoggerFactory.getLogger( FileConfigResolver.class );

    public FileConfigVolume resolve(FileConfigVolume aUnresolved, ResolverContext aContext) {

        File destination = aContext.fullDestination ();
        File source      = aContext.fullSource      ();
        File configFile  = aContext.fullConfig      ( aUnresolved.getConfigPath() );

        LOG.debug("destination: {}", destination.getAbsolutePath());
        LOG.debug("source     : {}", source.getAbsolutePath());
        LOG.debug("config     : {}", configFile.getAbsolutePath());

        if(!configFile.exists()) {
            throw new IllegalStateException("No config file "
                    + configFile.getAbsolutePath()
                    + "\n in uploaded dir " + aContext.getUploadedDirPath()
                    + "\n destination: " + destination.getAbsolutePath()
                    + "\n source     : " + source.getAbsolutePath()
                    + "\n config     : " + configFile.getAbsolutePath()
            );
        }

        aContext.fileSystem().copyFile(null, configFile, source);

        return FileConfigVolume.builder()
                .readonly    ( aUnresolved.isReadonly()     )
                .source      ( source.getAbsolutePath()     )
                .destination ( destination.getAbsolutePath())
                .configPath  ( aUnresolved.getConfigPath()  )
                .build();

    }
}
