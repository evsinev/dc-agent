package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.volumes.LinkToHostFileVolume;

public class LinkToHostFileResolver {

    public LinkToHostFileVolume resolve(LinkToHostFileVolume aUnresolved, ResolverContext aContext) {
        return LinkToHostFileVolume.builder()
                .source      ( aContext.fullSource().getAbsolutePath()      )
                .destination ( aContext.fullDestination().getAbsolutePath() )
                .readonly    ( aUnresolved.isReadonly()                     )
                .build();
    }
}
