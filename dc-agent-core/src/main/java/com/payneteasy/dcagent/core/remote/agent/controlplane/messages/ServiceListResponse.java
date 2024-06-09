package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ServiceListResponse {

    List<ServiceInfoItem> services;
}
