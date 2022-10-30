package com.payneteasy.dcagent.modules.docker.dirs;

import java.io.File;

public class ServicesLogDir {

    private final File logDir;

    public ServicesLogDir(File logDir) {
        this.logDir = logDir;
    }

    public File getServiceLogDir(String aName) {
        return new File(logDir, aName);
    }

}
