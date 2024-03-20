package com.payneteasy.dcagent.cli.config;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;
import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CliConfiguration {
    @Nonnull String          consumerKey;
    @Nonnull PrivateKey      clientPrivateKey;
    @Nonnull X509Certificate clientCertificate;
    @Nonnull X509Certificate caCertificate;
    @Nonnull File            clientCertificateFile;
    @Nonnull File            baseDir;
    @Nonnull String          baseUrl;
    @Nonnull String          openUrlCommand;
    @Nonnull List<String>    openUrlCommandArgs;

    public File getTaskDir(String aTaskName) {
        File tasksDir = new File(baseDir, "tasks");
        return new File(tasksDir, aTaskName);
    }
}
