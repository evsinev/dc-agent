package com.payneteasy.dcagent.modules.jar;

import com.payneteasy.dcagent.modules.jar.DaemontoolsServiceImpl.ServiceStatus;

import java.io.File;

public class ServiceLoopDetector implements WaitUrlCommand.ICancelCheck {

    private final DaemontoolsServiceImpl daemontoolsService;
    private final File                   serviceDir;
    private       int                    lastSeconds = 0;

    public ServiceLoopDetector(DaemontoolsServiceImpl daemontoolsService, File serviceDir) {
        this.daemontoolsService = daemontoolsService;
        this.serviceDir = serviceDir;
    }

    @Override
    public boolean isCancelled() {
        ServiceStatus status  = daemontoolsService.getServiceStatus(serviceDir);
        int           current = status.getSeconds();
        
        if (lastSeconds - current > 5) {
            throw new IllegalStateException("Service startup loop detected. Last seconds is " + lastSeconds + " but current is " + current);
        }
        lastSeconds = current;
        return false;
    }
}
