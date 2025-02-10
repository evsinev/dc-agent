package com.acme.dcagent.upload.core;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

import java.io.File;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ArtifactUploaderParams {
    @NonNull String baseUrl;
    @NonNull String artifactName;
    @NonNull String artifactVersion;

    File   fileToUpload;
    byte[] bytesToUpload;

    @NonNull String fileExtension;

    @NonNull String uploadKey;

}
