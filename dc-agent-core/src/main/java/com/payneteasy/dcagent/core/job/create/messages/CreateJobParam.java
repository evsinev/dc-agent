package com.payneteasy.dcagent.core.job.create.messages;

import com.payneteasy.dcagent.core.config.model.TaskType;
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
public class CreateJobParam {

    @Nonnull String       jobId;

    @Nonnull File     taskFile;
    @Nonnull TaskType taskType;
    @Nonnull String   taskName;
    @Nonnull String   taskHost;

    @Nonnull String          consumerKey;
    @Nonnull PrivateKey      privateKey;
    @Nonnull X509Certificate certificate;
    @Nonnull File            certificateFile;

}
