package com.payneteasy.dcagent.config.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TJarConfig implements IApiKeys, IGetTaskType {

    TaskType type;
    String   warFilename;
    String              jarFilename;
    String              serviceName;
    String              serviceDir;
    String              serviceStopTimeout;
    String              serviceStartTimeout;
    String              serviceLogFile;
    String              waitUrl;
    String              waitDuration;
    String              waitConnectTimeout;
    String              waitReadTimeout;
    String              svcCommand;
    String              svstatCommand;
    Map<String, String> apiKeys;

}
