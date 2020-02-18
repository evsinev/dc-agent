package com.payneteasy.dcagent.config.model;

import lombok.Data;

import java.util.Map;

@Data
public class TZipArchiveConfig implements IApiKeys {

    private final String              dir;
    private final Map<String, String> apiKeys;

}
