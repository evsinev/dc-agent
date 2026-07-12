package com.payneteasy.dcagent.operator.service.command.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TCommandInfo {

    String              host;
    String              name;
    TaskType            type;
    String              error;
    Map<String, String> parameters;
}
