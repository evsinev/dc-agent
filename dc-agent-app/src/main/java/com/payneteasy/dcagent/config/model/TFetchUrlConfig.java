package com.payneteasy.dcagent.config.model;

import lombok.Data;

import java.util.Map;

@Data
public class TFetchUrlConfig implements IApiKeys {

    private final Map<String, String> apiKeys;

}
