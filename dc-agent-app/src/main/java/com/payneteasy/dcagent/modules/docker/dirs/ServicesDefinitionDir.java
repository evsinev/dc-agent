package com.payneteasy.dcagent.modules.docker.dirs;

import java.io.File;

public class ServicesDefinitionDir {

    private final File serviceDefinitionDir;

    public ServicesDefinitionDir(File serviceDefinitionDir) {
        this.serviceDefinitionDir = serviceDefinitionDir;
    }

    public File getServiceDir(String aName) {
        return new File(serviceDefinitionDir, aName);
    }

    public File getServiceRunFile(String aName) {
        return new File(getServiceDir(aName), "run");
    }

    public File getServiceLogDir(String aName) {
        return new File(getServiceDir(aName), "log");
    }

    public File getServiceLogFile(String aName) {
        return new File(getServiceLogDir(aName), "run");
    }

}
