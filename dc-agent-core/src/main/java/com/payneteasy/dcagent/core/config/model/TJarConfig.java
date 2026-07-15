package com.payneteasy.dcagent.core.config.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder(toBuilder = true)
public class TJarConfig implements IApiKeys, IGetTaskType {

    @Getter(onMethod_ = @Override) TaskType type;
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
    @Getter(onMethod_ = @Override) Map<String, String> apiKeys;

}
