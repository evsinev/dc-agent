package com.payneteasy.dcagent.core.modules.docker.dirs;

import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.io.File;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class TempDir {

    File tempDir;

    public TempDir createDir() {
        tempDir.mkdirs();
        return this;
    }
}
