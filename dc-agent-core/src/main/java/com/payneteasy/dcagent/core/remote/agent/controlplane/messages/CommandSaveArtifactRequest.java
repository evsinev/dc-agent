package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Create/update a SAVE_ARTIFACT command. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandSaveArtifactRequest {

    String              name;
    TSaveArtifactConfig config;
    ApiKeyOps           apiKeys;
}
