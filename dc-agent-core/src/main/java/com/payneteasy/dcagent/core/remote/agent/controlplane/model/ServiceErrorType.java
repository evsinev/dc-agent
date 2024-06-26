package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

public enum ServiceErrorType {

    SUPERVISE_NOT_RUNNING,

    UNABLE_TO_OPEN_SUPERVISE_OK,

    UNABLE_TO_OPEN_SUPERVISE_STATUS,
    UNABLE_TO_READ_SUPERVISE_STATUS,

    UNABLE_TO_STAT_DOWN,
    UNABLE_TO_CHDIR,

    UNKNOWN_ERROR

}
