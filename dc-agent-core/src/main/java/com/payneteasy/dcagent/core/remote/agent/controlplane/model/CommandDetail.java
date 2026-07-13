package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

/**
 * Full detail of a single command for the view/edit screens: non-secret config fields and the
 * masked API keys. Secrets are never included.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandDetail {

    String              name;
    TaskType            type;
    Map<String, String> parameters;
    List<MaskedApiKey>  apiKeys;
}
