package com.payneteasy.dcagent.operator.service.command.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
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

    // Live state of the daemontools service named by parameters.serviceName (null when the command
    // has no serviceName or the service is not found on the agent). Resolved server-side so the
    // frontend needs no separate /service/list request.
    String              serviceStatusName;
    StatusIndicator     serviceStatusIndicator;
}
