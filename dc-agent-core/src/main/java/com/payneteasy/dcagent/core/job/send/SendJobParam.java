package com.payneteasy.dcagent.core.job.send;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;
import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class SendJobParam {

    @Nonnull String          baseUrl;
    @Nonnull PrivateKey      clientPrivateKey;
    @Nonnull X509Certificate clientCertificate;
    @Nonnull X509Certificate caCertificate;
    @Nonnull File            jobFile;
    @Nonnull String          jobId;

}
