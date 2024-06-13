package com.payneteasy.dcagent.operator.service.services.messages;

import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class HostServiceViewResponse {
    HostServiceItem service;
    String          runContent;
    String          logRunContent;
    String          lastLogLines;
}
