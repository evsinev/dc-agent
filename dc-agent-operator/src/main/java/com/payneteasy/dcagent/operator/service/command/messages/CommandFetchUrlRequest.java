package com.payneteasy.dcagent.operator.service.command.messages;

import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandFetchUrlRequest {

    String          host;
    String          name;
    TFetchUrlConfig config;
    ApiKeyOps       apiKeys;
}
