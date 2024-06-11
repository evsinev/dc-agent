package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class StatusDescription {
    String          name;
    StatusIndicator indicator;

    public static StatusDescription description(String aName, StatusIndicator aIndicator) {
        return new StatusDescription(aName, aIndicator);
    }
}
