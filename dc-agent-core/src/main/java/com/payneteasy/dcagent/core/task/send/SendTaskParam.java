package com.payneteasy.dcagent.core.task.send;

import com.payneteasy.dcagent.core.config.model.TaskType;

import javax.annotation.Nonnull;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class SendTaskParam {

    @Nonnull String   agentBaseUrl;
    @Nonnull String   accessToken;
    @Nonnull byte[]   taskBytes;
    @Nonnull String   taskName;
    @Nonnull TaskType taskType;

}
