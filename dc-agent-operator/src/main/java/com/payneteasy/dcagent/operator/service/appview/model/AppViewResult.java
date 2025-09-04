package com.payneteasy.dcagent.operator.service.appview.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class AppViewResult {
    String   appName;
    String   taskName;
    TaskType taskType;
    String   taskHost;
    String   taskCheckText;
    String   taskCheckColor;
    String   jobCreatedDateFormatted;
    String   consumerKey;
    String   agentUrl;
}
