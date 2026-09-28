package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.security.TIdRef;
import com.payneteasy.dcagent.core.config.model.docker.security.TIdSource;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;

/**
 * Replaces references to {@code securityContext} fields with numbers. A reference to a field
 * that is not set is always an error — also for the {@code owner: runAs} shorthand, which
 * therefore needs both runAsUser and runAsGroup.
 */
public class VolumeOwnerResolver {

    /**
     * @param aPath config path of the owner for error messages, e.g. {@code volumes[0].directoryOrCreate.owner}
     * @return the owner with only numeric ids, or {@code null} when there is no owner
     */
    public TVolumeOwner resolve(TVolumeOwner aOwner, TSecurityContext aContext, String aPath) {
        if (aOwner == null) {
            return null;
        }
        return TVolumeOwner.builder()
                .user  ( resolveId(aOwner.getUser() , aContext, aPath + ".user" ) )
                .group ( resolveId(aOwner.getGroup(), aContext, aPath + ".group") )
                .build();
    }

    private TIdRef resolveId(TIdRef aId, TSecurityContext aContext, String aPath) {
        if (aId == null || aId.getRef() == null) {
            return aId;
        }

        TIdSource source = aId.getRef();
        Integer   id     = aContext == null ? null : idOf(source, aContext);
        if (id == null) {
            throw new IllegalStateException(aPath + " refers to securityContext." + source.yamlName()
                    + ", which is not set (owner: runAs needs both runAsUser and runAsGroup)");
        }
        return TIdRef.builder().id(id).build();
    }

    private static Integer idOf(TIdSource aSource, TSecurityContext aContext) {
        switch (aSource) {
            case RUN_AS_USER:
                return aContext.getRunAsUser();
            case RUN_AS_GROUP:
                return aContext.getRunAsGroup();
            default:
                throw new IllegalStateException("Unknown id source " + aSource);
        }
    }
}
