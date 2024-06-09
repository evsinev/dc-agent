package com.payneteasy.dcagent.operator.service.services.messages;

import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class HostServiceListResponse {
    List<HostServiceItem> services;
}
