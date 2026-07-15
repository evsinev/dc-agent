package com.payneteasy.dcagent.core.config.model;

import lombok.Data;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder(toBuilder = true)
@Getter(onMethod_ = @Override)
public class TFetchUrlConfig implements IApiKeys, IGetTaskType {

    Map<String, String> apiKeys;
    TaskType            type;

}
