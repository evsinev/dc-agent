package com.payneteasy.dcagent.core.config.model.docker;

import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.config.model.IGetTaskType;
import com.payneteasy.dcagent.core.config.model.TaskType;

import java.util.Map;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TDockerConfig implements IApiKeys, IGetTaskType {

    TaskType            type;
    Map<String, String> apiKeys;

}
