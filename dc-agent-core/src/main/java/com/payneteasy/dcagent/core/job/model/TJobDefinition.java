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
public class TJobDefinition {

    @Nonnull String   taskName;
    @Nonnull TaskType taskType;
    @Nonnull String   jobId;

    @Nonnull TJobSignatureParam signatureParam;
    @Nonnull String             signatureBaseString;
    @Nonnull byte[]             signature;

}
