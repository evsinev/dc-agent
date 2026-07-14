package com.payneteasy.dcagent.core.modules.docker.dirs;

import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.io.File;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class TempDir {

    File    tempDir;
    // Whether working dirs extracted under this temp root should be removed after use
    // (docker push/check). Controlled by the DOCKER_DELETE_TEMP_DIR startup flag.
    boolean deleteAfterExtract;

    public TempDir createDir() {
        tempDir.mkdirs();
        return this;
    }
}
