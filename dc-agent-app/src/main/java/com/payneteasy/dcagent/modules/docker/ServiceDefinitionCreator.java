package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.config.model.docker.Owner;
import com.payneteasy.dcagent.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.modules.docker.filesystem.IFileSystem;

public class ServiceDefinitionCreator {

    private final ServicesDefinitionDir servicesDefinitionDir;
    private final IFileSystem           fileSystem;

    public ServiceDefinitionCreator(ServicesDefinitionDir servicesDefinitionDir, IFileSystem fileSystem) {
        this.servicesDefinitionDir = servicesDefinitionDir;
        this.fileSystem            = fileSystem;
    }

    public void createService(String aName, String aMainRun, String aLogRun, Owner aOwner) {
        createMainDir(aOwner, aName);
        writeMainRun(aOwner, aName, aMainRun);

        createLogDir(aOwner, aName);
        writeLogRun(aOwner, aName, aLogRun);
    }

    private void writeLogRun(Owner aOwner, String aName, String aLogRun) {
        fileSystem.writeExecutable(aOwner, servicesDefinitionDir.getServiceLogFile(aName), aLogRun);
    }

    private void createLogDir(Owner aOwner, String aName) {
        fileSystem.createDirectories(aOwner, servicesDefinitionDir.getServiceLogDir(aName));
    }

    private void writeMainRun(Owner aOwner, String aName, String aMainRun) {
        fileSystem.writeExecutable(aOwner, servicesDefinitionDir.getServiceRunFile(aName), aMainRun);
    }

    private void createMainDir(Owner aOwner, String aName) {
        fileSystem.createDirectories(aOwner, servicesDefinitionDir.getServiceDir(aName));
    }
}
