package com.payneteasy.dcagent.core.config.model;

import lombok.Data;

import java.util.Map;

import lombok.Builder;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TFetchUrlConfig implements IApiKeys, IGetTaskType {

    Map<String, String> apiKeys;
    TaskType            type;

}
