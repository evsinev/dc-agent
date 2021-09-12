package com.payneteasy.dcagent.config.model;

import lombok.Data;

import java.util.Map;

@Data
public class TJarConfig implements IApiKeys {

    private final String              warFilename;
    private final String              jarFilename;
    private final String              serviceDir;
    private final String              serviceStopTimeout;
    private final String              serviceStartTimeout;
    private final String              serviceLogFile;
    private final String              waitUrl;
    private final String              waitDuration;
    private final String              waitConnectTimeout;
    private final String              waitReadTimeout;
    private final String              svcCommand;
    private final String              svstatCommand;
    private final Map<String, String> apiKeys;

}
