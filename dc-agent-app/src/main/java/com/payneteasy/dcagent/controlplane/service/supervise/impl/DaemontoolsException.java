package com.payneteasy.dcagent.controlplane.service.supervise.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceErrorType;

public class DaemontoolsException extends IllegalStateException {

    private final ServiceErrorType error;

    public DaemontoolsException(ServiceErrorType error, String s) {
        super(s);
        this.error = error;
    }

    public ServiceErrorType getError() {
        return error;
    }
}
