package com.payneteasy.dcagent.controlplane.service.daemontools.impl;

import com.payneteasy.dcagent.controlplane.service.daemontools.model.SuperviseStatusFile;
import com.payneteasy.dcagent.controlplane.service.daemontools.model.WantStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceErrorType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.SuperviseState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;

public class ServiceStatusParser {

    private final SuperviseStatusFileParser superviseStatusFileParser = new SuperviseStatusFileParser();

    public ServiceStatus parseServiceStatus(File aServiceDir) {
        File superviseDir = new File(aServiceDir, "supervise");

        SuperviseStatusFile status = getParseStatusFile(superviseDir);

        return ServiceStatus.builder()
                .pid   ( status.getPid())
                .state ( parseStatus(superviseDir, status))
                .when  ( new Date(status.getWhen()))
                .superviseState( isRunning(superviseDir))
                .build();
    }

    private ServiceStateType parseStatus(File aFile, SuperviseStatusFile status) {
        boolean normallyUp = !new File(aFile, "down").exists();
        boolean hasPid     = status.getPid() > 0;

        if (hasPid && !normallyUp) {
            return ServiceStateType.UP_NORMALLY_DOWN;
        }

        if (!hasPid && normallyUp) {
            return ServiceStateType.DOWN_NORMALLY_UP;
        }

        if (hasPid && status.isPaused()) {
            return ServiceStateType.UP_PAUSED;
        }

        if (!hasPid && status.getWant() == WantStateType.WANT_UP) {
            return ServiceStateType.DOWN_WANT_UP;
        }

        if (hasPid && status.getWant() == WantStateType.WANT_DOWN) {
            return ServiceStateType.UP_WANT_DOWN;
        }

        return hasPid ? ServiceStateType.UP : ServiceStateType.DOWN;

    }

    private SuperviseState isRunning(File aFile) {
        return new File(aFile, "ok").exists() ? SuperviseState.SUPERVISE_RUNNING : SuperviseState.SUPERVISE_NOT_RUNNING;
    }

    private SuperviseStatusFile getParseStatusFile(File aFile) {
        File statusFile = new File(aFile, "status");
        try {
            return superviseStatusFileParser.parseStatusFile(
                    Files.readAllBytes(statusFile.toPath())
            );
        } catch (IOException e) {
            throw new DaemontoolsException(ServiceErrorType.UNABLE_TO_OPEN_SUPERVISE_STATUS, "Cannot read file " + statusFile.getAbsolutePath());
        }
    }
}
