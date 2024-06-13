package com.payneteasy.dcagent.operator.service.services.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceActionType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class HostServiceSendActionRequest {

    String            fqsn;
    ServiceActionType serviceAction;
}
