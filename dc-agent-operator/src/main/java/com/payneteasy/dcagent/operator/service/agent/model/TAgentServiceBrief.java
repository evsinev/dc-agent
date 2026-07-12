package com.payneteasy.dcagent.operator.service.agent.model;

import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TAgentServiceBrief {

    String          serviceName;
    String          statusName;
    StatusIndicator statusIndicator;
}
