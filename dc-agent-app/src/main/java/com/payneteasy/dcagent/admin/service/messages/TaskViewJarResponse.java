package com.payneteasy.dcagent.admin.service.messages;

import com.payneteasy.dcagent.config.model.TJarConfig;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TaskViewJarResponse {
    String     taskName;
    TJarConfig jarConfig;
}