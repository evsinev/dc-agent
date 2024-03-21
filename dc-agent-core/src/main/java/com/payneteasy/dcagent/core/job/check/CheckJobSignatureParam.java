package com.payneteasy.dcagent.core.job.check;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;
import java.security.cert.X509Certificate;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CheckJobSignatureParam {

    @Nonnull X509Certificate consumerCertificate;
    @Nonnull byte[]          taskZipFileBytes;
    @Nonnull byte[]          jobJsonFileBytes;
    @Nonnull byte[]          jobSignatureJsonFileBytes;

}
