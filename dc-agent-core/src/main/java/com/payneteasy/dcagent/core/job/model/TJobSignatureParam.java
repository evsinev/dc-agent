package com.payneteasy.dcagent.core.job.model;

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

    @Nonnull String                 consumerKey;
    @Nonnull byte[]                 hash;
    @Nonnull JobHashType            hashType;
    @Nonnull String                 jobId;
    @Nonnull JobSignatureMethodType signatureMethod;
    @Nonnull TaskType               taskType;
    @Nonnull Long                   timestampMs;

}
