package com.payneteasy.dcagent.core.job.create.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TJobSignatureParam {

    @Nonnull String             consumerKey;
    @Nonnull byte[]             taskFileHash;
    @Nonnull JobHashType        taskFileHashType;
    @Nonnull String             nonce;
    @Nonnull TaskType           taskType;
    @Nonnull Long               timestampMs;

}
