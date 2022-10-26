package com.payneteasy.dcagent.admin.service.messages;

import com.payneteasy.dcagent.admin.service.model.TaskListItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TaskListResponse {

    List<TaskListItem> items;

}
