package com.payneteasy.dcagent.config.model;

import lombok.Data;

import java.util.Map;

@Data
public class TSaveArtifactConfig implements IApiKeys {

    private final String              dir;
    private final Map<String, String> apiKeys;
    private final String              extension;
}
