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
public class TSaveArtifactConfig implements IApiKeys, IGetTaskType {

    @Getter(onMethod_ = @Override) TaskType            type;
    String              dir;
    @Getter(onMethod_ = @Override) Map<String, String> apiKeys;
    String              extension;
    String              replaceDirChars;
}
