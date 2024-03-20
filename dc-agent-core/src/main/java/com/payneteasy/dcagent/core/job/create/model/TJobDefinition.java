package com.payneteasy.dcagent.core.job.create.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TJobDefinition {

    @Nonnull String   taskName;
    @Nonnull TaskType taskType;
    @Nonnull String   jobId;

    @Nonnull TJobSignatureParam signatureParam;

    @Nonnull List<String> taskHosts;
}
