package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** Create/update a ZIP_DIRS command. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandZipDirsRequest {

    String         name;
    TZipDirsConfig config;
    ApiKeyOps      apiKeys;
}
