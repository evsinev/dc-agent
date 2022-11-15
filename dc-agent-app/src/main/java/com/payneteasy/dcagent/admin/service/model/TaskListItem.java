package com.payneteasy.dcagent.admin.service.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TaskListItem {
    String        taskName;
    TaskType      taskType;
    TaskStateType taskState;
    String        hostname;
    String        description;
    String        runningTime;
}
