package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Date;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ServiceStatus {

    ServiceStateType state;
    ServiceErrorType error;
    long             pid;
    Date           when;
    SuperviseState superviseState;

}
