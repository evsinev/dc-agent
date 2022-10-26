package com.payneteasy.dcagent.config.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TSaveArtifactConfig implements IApiKeys, IGetTaskType {

    TaskType type;
    String   dir;
    Map<String, String> apiKeys;
    String              extension;
}
