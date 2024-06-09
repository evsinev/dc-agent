package com.payneteasy.dcagent.operator.service.services.model;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class HostServiceItem {
    String        fqsn;
    String        host;
    String        serviceName;
    ServiceStatus serviceStatus;
    String        errorMessage;
}
