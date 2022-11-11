package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.volumes.TemplateFileConfigVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class TemplateFileConfigResolver {

    private static final Logger LOG = LoggerFactory.getLogger( TemplateFileConfigResolver.class );

    public TemplateFileConfigVolume resolve(TemplateFileConfigVolume aUnresolved, ResolverContext aContext) {

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

        aContext.fileSystem().copyTemplateFile(null, configFile, source, aContext.getResolvedBoundVariables());

        return TemplateFileConfigVolume.builder()
                .readonly    ( aUnresolved.isReadonly()     )
                .source      ( source.getAbsolutePath()     )
                .destination ( destination.getAbsolutePath())
                .configPath  ( aUnresolved.getConfigPath()  )
                .build();

    }
}
