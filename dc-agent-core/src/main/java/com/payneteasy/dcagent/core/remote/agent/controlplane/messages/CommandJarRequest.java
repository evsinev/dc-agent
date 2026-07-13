package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Create/update a JAR command. The embedded {@code config}'s own {@code type}/{@code apiKeys}
 * are ignored — the service forces {@code type=JAR} and derives keys from {@link #apiKeys}.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandJarRequest {

    String     name;
    TJarConfig config;
    ApiKeyOps  apiKeys;
}
