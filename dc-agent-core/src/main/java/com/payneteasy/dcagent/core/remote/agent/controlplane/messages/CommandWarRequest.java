package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Create/update a WAR command (shares the {@link TJarConfig} model; service forces type=WAR). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandWarRequest {

    String     name;
    TJarConfig config;
    ApiKeyOps  apiKeys;
}
