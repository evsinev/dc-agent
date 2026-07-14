package com.payneteasy.dcagent.operator.service.command.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.MaskedApiKey;
import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

/** Frontend-facing detail for a single command: the agent host plus the (masked) config. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TCommandDetail {

    String              host;
    String              name;
    TaskType            type;
    Map<String, String> parameters;
    List<MaskedApiKey>  apiKeys;

    // Live state of the daemontools service named by parameters.serviceName (null when absent),
    // resolved server-side so Command View needs no separate /service/list request.
    String              serviceStatusName;
    StatusIndicator     serviceStatusIndicator;
}
