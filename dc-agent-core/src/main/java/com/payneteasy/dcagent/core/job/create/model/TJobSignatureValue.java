package com.payneteasy.dcagent.core.job.create.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TJobSignatureValue {
    @Nonnull byte[]             jobFileHash;
    @Nonnull JobHashType        jobFileHashType;
    @Nonnull byte[]             signature;
    @Nonnull JobSignatureMethod signatureMethod;
}
