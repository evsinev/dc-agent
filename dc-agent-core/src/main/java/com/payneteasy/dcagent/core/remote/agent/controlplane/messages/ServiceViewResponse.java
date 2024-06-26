package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ServiceViewResponse {

    String          serviceName;
    String          runContent;
    String          logRunContent;
    String          lastLogLines;
    ServiceInfoItem serviceInfo;

}
