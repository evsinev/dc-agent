package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.volumes.LinkToHostDirectoryVolume;

public class LinkToHostDirectoryResolver {

    public LinkToHostDirectoryVolume resolve(LinkToHostDirectoryVolume aUnresolved, ResolverContext aContext) {
        return LinkToHostDirectoryVolume.builder()
                .source      ( aContext.fullSource().getAbsolutePath()      )
                .destination ( aContext.fullDestination().getAbsolutePath() )
                .readonly    ( aUnresolved.isReadonly()                     )
                .build();
    }
}
