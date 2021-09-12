package com.payneteasy.dcagent.modules.jar;

import com.payneteasy.osprocess.api.IProcessService;
import com.payneteasy.osprocess.api.ProcessDescriptor;
import com.payneteasy.osprocess.api.ProcessException;
import com.payneteasy.osprocess.api.ProcessRunResult;
import com.payneteasy.osprocess.impl.ProcessServiceImpl;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.StringTokenizer;

import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class DaemontoolsServiceImpl {

    private static final Logger LOG = LoggerFactory.getLogger(DaemontoolsServiceImpl.class);

    private final String svcCommand;
    private final String svstatCommand;
    private final ILog   log;

    private final IProcessService processService = new ProcessServiceImpl();


    public DaemontoolsServiceImpl(String svcCommand, String svstatCommand, ILog log) {
        this.svcCommand = svcCommand;
        this.svstatCommand = svstatCommand;
        this.log = log;
    }

    public void stopService(File aServiceDir, Duration aWaitDuration) throws ProcessException, InterruptedException {
        svc("Stop", aServiceDir.getAbsolutePath(), "-d");
        svstat("Stopped", aServiceDir.getAbsolutePath(), ": down ", aWaitDuration);
    }

    public void startService(File aServiceDir, Duration aTimeout) throws ProcessException, InterruptedException {
        svc("Start", aServiceDir.getAbsolutePath(), "-u");
        svstat("Started", aServiceDir.getAbsolutePath(), ": up ", aTimeout);
    }

    private void svc(String aName, String aServiceDir, String aOption) throws ProcessException {
        log.debug("%s service %s", aName, aServiceDir);
        ProcessDescriptor descriptor = new ProcessDescriptor(
                svcCommand
                , asList(aOption, aServiceDir)
                , emptyList()
                , new File(aServiceDir)
        );
        ProcessRunResult result = processService.runProcess(descriptor);
        if(result.getExitCode() != 0) {
            throw new IllegalStateException("Cannot stop service " + result.getOutput());
        }
    }

    private void svstat(String aName, String aServiceDir, String aSubstring, Duration aTimeout) throws ProcessException, InterruptedException {
        log.debug("Waiting for service %s to be %s", aServiceDir, aName);

        long endTime = currentTimeMillis() + aTimeout.toMillis();

        while( currentTimeMillis() < endTime) {
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("svstat interrupted");
            }
            
            ProcessDescriptor descriptor = new ProcessDescriptor(
                    svstatCommand
                    , singletonList(aServiceDir)
                    , emptyList()
                    , new File(aServiceDir)
            );
            ProcessRunResult result = processService.runProcess(descriptor);
            if(result.getExitCode() != 0) {
                throw new IllegalStateException("Cannot check service " + result.getOutput());
            }

            if(result.getOutput().contains(aSubstring)) {
                return;
            }

            log.debug(result.getOutput());
            Thread.sleep(1_000);
        }

        throw new IllegalStateException("Cannot " + aName + "  service. It's still running.");

    }

    public ServiceStatus getServiceStatus(File aServiceDir) {
        ProcessDescriptor descriptor = new ProcessDescriptor(
                svstatCommand
                , singletonList(aServiceDir.getAbsolutePath())
                , emptyList()
                , aServiceDir
        );
        ProcessRunResult result;
        try {
            result = processService.runProcess(descriptor);
        } catch (ProcessException e) {
            throw new IllegalStateException("Cannon run svstat", e);
        }

        if(result.getExitCode() != 0) {
            throw new IllegalStateException("Cannot check service " + result.getOutput());
        }

        // /service/paynet-tomcat-paynet-1/: up (pid 11024) 4 seconds

        String text = result.getOutput()
                .substring(result.getOutput().indexOf(':'))
                .replace("(pid", "");

        LOG.debug("Svstat output is '{}'", result.getOutput());
        LOG.debug("Svstat status text is '{}'", text);

        StringTokenizer st = new StringTokenizer(text, " :()");
        String status = st.nextToken();
        int pid;
        if("up".equals(status)) {
            pid = Integer.parseInt(st.nextToken());
        } else {
            pid = -1;
        }
        int seconds = Integer.parseInt(st.nextToken());
        return new ServiceStatus(status, pid, seconds);
    }

    @Data
    public static class ServiceStatus {
        private final String status;
        private final int pid;
        private final int seconds;
    }

}
