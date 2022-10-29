package com.payneteasy.dcagent.config.model.docker.volumes;

public interface IVolume {

    String getSource();

    String getDestination();

    boolean isReadonly();
}
