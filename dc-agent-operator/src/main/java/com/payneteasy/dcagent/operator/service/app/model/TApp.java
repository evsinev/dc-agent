package com.payneteasy.dcagent.operator.service.app.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TApp {
    String   appName;
    String   taskName;
    String   taskHost;
    TaskType taskType;
}
