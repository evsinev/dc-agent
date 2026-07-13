package com.payneteasy.dcagent.operator.service.command.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.MaskedApiKey;
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
}
