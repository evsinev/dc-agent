package com.payneteasy.dcagent.operator.service.agent.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TAgentInfo {

    // config
    String  name;
    String  url;

    // /app-status
    boolean reachable;
    String  error;
    String  appInstanceName;
    String  appVersion;
    String  hostname;
    int     port;
    long    uptimeMs;
    String  uptimeFormatted;
    long    responseEpoch;
    String  responseId;

    // control-plane services
    int                      servicesTotal;
    int                      servicesUp;
    String                   servicesError;
    List<TAgentServiceBrief> services;

    // control-plane system/JVM metrics
    TAgentMetrics            metrics;
    String                   metricsError;
}
