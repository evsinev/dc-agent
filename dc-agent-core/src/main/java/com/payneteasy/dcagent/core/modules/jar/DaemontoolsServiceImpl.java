package com.payneteasy.dcagent.core.modules.jar;

import com.payneteasy.dcagent.core.daemontools.Supervise;
import com.payneteasy.dcagent.core.daemontools.SuperviseStatusFile;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.time.Duration;

import static java.lang.System.currentTimeMillis;

/**
 * Stops and starts a daemontools service through its supervise directory ({@link Supervise}) — no
 * {@code svc}/{@code svstat} processes.
 */
public class DaemontoolsServiceImpl {

    /** How long a running supervise may take to accept the control or ok fifo. */
    public static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);

    private final Supervise supervise;
    private final ILog      log;
    private final Duration  pollInterval;

    public DaemontoolsServiceImpl(ILog log) {
        this(new Supervise(OPEN_TIMEOUT), log, Duration.ofSeconds(1));
    }

    public DaemontoolsServiceImpl(Supervise aSupervise, ILog aLog, Duration aPollInterval) {
        supervise    = aSupervise;
        log          = aLog;
        pollInterval = aPollInterval;
    }

    public void stopService(File aServiceDir, Duration aWaitDuration) throws IOException, InterruptedException {
        send(aServiceDir, 'd');
        waitUntil(aServiceDir, false, aWaitDuration);
    }

    public void startService(File aServiceDir, Duration aTimeout) throws IOException, InterruptedException {
        send(aServiceDir, 'u');
        waitUntil(aServiceDir, true, aTimeout);
    }

    /**
     * Sends one supervise command ({@code u}, {@code d}, {@code h}, {@code t}, …), as {@code svc -<command>}.
     * Returns once the byte is written: the command is delivered.
     */
    public void send(File aServiceDir, char aCommand) throws IOException, InterruptedException {
        log.debug("Sending %s to service %s", aCommand, aServiceDir.getAbsolutePath());
        supervise.send(aServiceDir.toPath(), aCommand);
    }

    /** {@link #send} carried through an interrupt (kept for the caller): for a rollback. */
    public void sendUninterruptibly(File aServiceDir, char aCommand) throws IOException {
        log.debug("Sending %s to service %s", aCommand, aServiceDir.getAbsolutePath());
        supervise.sendUninterruptibly(aServiceDir.toPath(), aCommand);
    }

    /** The status, read as before a stop: supervise runs and its status file is one this agent reads. */
    public SuperviseStatusFile readStatus(File aServiceDir) throws IOException, InterruptedException {
        return supervise.status(aServiceDir.toPath());
    }

    /** Waits until the service has a process ({@code aUp}) or none. */
    public void waitUntil(File aServiceDir, boolean aUp, Duration aTimeout) throws IOException, InterruptedException {
        String state = aUp ? "up" : "down";
        log.debug("Waiting for service %s to be %s", aServiceDir.getAbsolutePath(), state);

        long endTime = currentTimeMillis() + aTimeout.toMillis();
        while (true) {
            SuperviseStatusFile status = supervise.status(aServiceDir.toPath());
            if ((status.getPid() != 0) == aUp) {
                return;
            }
            if (currentTimeMillis() >= endTime) {
                throw new IllegalStateException("Service " + aServiceDir.getAbsolutePath() + " is not " + state
                        + " after " + aTimeout.toSeconds() + " s (" + describe(status) + ")");
            }
            log.debug("Service %s: %s", aServiceDir.getAbsolutePath(), describe(status));
            Thread.sleep(pollInterval.toMillis());
        }
    }

    public ServiceStatus getServiceStatus(File aServiceDir) {
        SuperviseStatusFile status;
        try {
            status = supervise.status(aServiceDir.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the status of " + aServiceDir.getAbsolutePath() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the status of " + aServiceDir.getAbsolutePath(), e);
        }
        long seconds = Math.max(0, (currentTimeMillis() - status.getWhen()) / 1000);
        return new ServiceStatus(status.getPid() == 0 ? "down" : "up", (int) status.getPid(), (int) seconds);
    }

    private static String describe(SuperviseStatusFile aStatus) {
        return (aStatus.getPid() == 0 ? "down" : "up, pid " + aStatus.getPid()) + ", want " + aStatus.getWant();
    }

    @Data
    public static class ServiceStatus {
        private final String status;
        private final int pid;
        private final int seconds;
    }

}
