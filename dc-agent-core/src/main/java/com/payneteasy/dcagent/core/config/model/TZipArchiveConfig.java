package com.payneteasy.dcagent.core.config.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TZipArchiveConfig implements IApiKeys, IGetTaskType {

    TaskType type;
    String   dir;
    Map<String, String> apiKeys;

}
