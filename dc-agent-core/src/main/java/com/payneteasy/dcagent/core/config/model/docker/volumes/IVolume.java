package com.payneteasy.dcagent.core.config.model.docker.volumes;

public interface IVolume {

    String getSource();

    String getDestination();

    boolean isReadonly();
}
