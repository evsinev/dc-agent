package com.payneteasy.dcagent.controller.service.jobview;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class JobViewResult {
    String   jobId;
    String   taskName;
    TaskType taskType;
    String   taskHost;
    String   taskCheckText;
    String   jobCreatedDateFormatted;
    String   consumerKey;
}
