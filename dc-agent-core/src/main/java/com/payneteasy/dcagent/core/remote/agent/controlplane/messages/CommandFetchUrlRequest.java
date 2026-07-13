package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Create/update the FETCH_URL command (fixed name {@code fetch-url}; no config fields). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandFetchUrlRequest {

    String          name;
    TFetchUrlConfig config;
    ApiKeyOps       apiKeys;
}
