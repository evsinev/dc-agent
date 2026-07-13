package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Create/update the DOCKER command (fixed name {@code dc-docker}). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandDockerRequest {

    String        name;
    TDockerConfig config;
    ApiKeyOps     apiKeys;
}
