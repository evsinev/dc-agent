package com.payneteasy.dcagent.core.remote.agent.appstatus;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Response of the agent's jetty-util {@code /app-status/} endpoint.
 * Field names match the JSON produced by {@code com.payneteasy.jetty.util.appstatus.messages.AppStatusResponse}.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TAgentAppStatus {

    String type;
    String appInstanceName;
    String appVersion;
    String hostname;
    int    port;
    String responseId;
    long   responseEpoch;
    long   uptimeMs;
}
