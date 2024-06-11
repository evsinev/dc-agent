package com.payneteasy.dcagent.controlplane.service.serviceview;

import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.io.File;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ServiceFiles {

    File serviceDir;
    File runFile;
    File logDir;
    File logRunFile;

    public static ServiceFiles createServiceFiles(File aServicesDir, String aName) {
        File serviceDir = new File(aServicesDir, aName);
        File runFile    = new File(serviceDir, "run");
        File logDir     = new File(serviceDir, "log");
        File logRunFile = new File(logDir, "run");

        return new ServiceFiles(
                serviceDir
                , runFile
                , logDir
                , logRunFile
        );
    }
}
