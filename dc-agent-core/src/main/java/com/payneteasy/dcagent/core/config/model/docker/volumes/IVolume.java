package com.payneteasy.dcagent.core.config.model.docker.volumes;

import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;

public interface IVolume {

    String getSource();

    String getDestination();

    boolean isReadonly();

    /**
     * Owner of the host directory. Supported only by {@code directoryOrCreate}; declared on every
     * volume type so that Gson does not drop it silently and the resolver can reject it.
     */
    TVolumeOwner getOwner();

    /** Permissions of the host directory, e.g. {@code "0770"}. Same rules as {@link #getOwner()}. */
    String getMode();
}
