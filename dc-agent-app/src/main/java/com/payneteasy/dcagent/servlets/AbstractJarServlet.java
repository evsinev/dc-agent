package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.util.Strings;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.jar.*;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.jetty.CommandAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Date;

import static com.payneteasy.dcagent.core.util.Strings.hasText;
import static com.payneteasy.dcagent.core.util.Units.parseDuration;
import static java.nio.file.Files.move;

public abstract class AbstractJarServlet extends HttpServlet {

    private final Logger LOG = LoggerFactory.getLogger(getClass());

    /**
     * Services being deployed (jar, war and node alike): one deploy per service at a time, so two
     * deploys never interleave their stop, jar replacement and start — nor their rollbacks.
     */
    private static final java.util.concurrent.ConcurrentMap<String, Boolean> DEPLOYING = new java.util.concurrent.ConcurrentHashMap<>();

    private final CommandAuth commandAuth;

    private final IConfigService         configService;
    private final DaemontoolsServiceImpl daemontoolsService;

    public AbstractJarServlet(IConfigService configService, DaemontoolsServiceImpl daemontoolsService) {
        commandAuth = new CommandAuth(configService);
        this.configService      = configService;
        this.daemontoolsService = daemontoolsService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {

        PathParameters parameters = new PathParameters(aRequest.getRequestURI());
        String         name       = parameters.getLast();
        // nothing — body, File objects from the config, the service — before the command is authorized
        TJarConfig     jarConfig  = commandAuth.authorize(aRequest, name, expectedType(), configService::getJarConfig);
        File           serviceDir = new File(getDefault(jarConfig.getServiceDir(), "/service/" + jarConfig.getServiceName()));
        File           jarFile    = getJarFile(jarConfig);
        File           logFile    = new File(getDefault(jarConfig.getServiceLogFile(), "/var/log/" + jarConfig.getServiceName() + "/current"));

        String service = service(serviceDir);
        try {
            if (DEPLOYING.putIfAbsent(service, Boolean.TRUE) != null) {
                aResponse.setStatus(HttpServletResponse.SC_CONFLICT);
                aResponse.getWriter().write("Another deploy of service " + service + " is running\n");
                aResponse.getWriter().flush();
                return;
            }
            try {
                deploy(aRequest, aResponse, name, jarConfig, serviceDir, jarFile, logFile);
            } finally {
                DEPLOYING.remove(service);
            }
        } catch (IOException e) { // the answer could not be written: the client is gone; the deploy itself is done
            LOG.warn("Cannot write the answer for {}: {}", Strings.forLog(name), e.toString());
        }
    }

    /** The service itself, whatever link names it ({@code /service/app -> /etc/service.d/app}). */
    private static String service(File aServiceDir) {
        try {
            return aServiceDir.toPath().toRealPath().toString();
        } catch (IOException e) {
            return aServiceDir.toPath().toAbsolutePath().normalize().toString(); // missing: the deploy fails anyway
        }
    }

    private void deploy(HttpServletRequest aRequest, HttpServletResponse aResponse, String name, TJarConfig jarConfig,
                        File serviceDir, File jarFile, File logFile) throws IOException {

        StringBuffer  sb = new StringBuffer();
        ILog log = (aFormat, args) -> {
            String message = String.format(aFormat, args);
            sb.append('\n').append(new Date()).append(' ').append(message);
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}: {}", Strings.forLog(name), Strings.forLog(message));
            }
        };

        log.debug("Jar file %s", jarFile.getAbsolutePath());
        log.debug("Log file %s", logFile.getAbsolutePath());

        log.debug("Processing %s...", name);

        // set once supervise has the stop / start command (the byte is written): a deploy that fails
        // in between asks it to start again, so a failed deploy never leaves the service down — and
        // a service the agent did not stop is never started
        boolean stopSent  = false;
        boolean startSent = false;
        try {
            // everything that can fail without touching the service comes first
            Duration stopTimeout  = parseDuration(jarConfig.getServiceStopTimeout() , "30s");
            Duration startTimeout = parseDuration(jarConfig.getServiceStartTimeout(), "10s");
            WaitUrlCommand waitUrl = new WaitUrlCommand()
                    .setWaitUrl             ( jarConfig.getWaitUrl() )
                    .setConnectTimeout      ( parseDuration( jarConfig.getWaitConnectTimeout(), "30s"))
                    .setReadTimeout         ( parseDuration( jarConfig.getWaitReadTimeout()   , "30s"))
                    .setWaitDuration        ( parseDuration( jarConfig.getWaitDuration()      , "3m"))
                    .setLog                 ( log )
                    .setCheck               ( new ServiceLoopDetector(daemontoolsService, serviceDir));

            try (TempFile tempFile = new TempFile(name, "jar")) {
                log.debug("Saving file to temp location %s ...", tempFile.getFile().getAbsolutePath());
                tempFile.writeFromInputStream(aRequest.getInputStream());

                // supervise runs and its status is one the agent reads (daemontools' 18 bytes), or
                // the deploy stops here, before the service is touched
                daemontoolsService.readStatus(serviceDir);

                stopSent = sent(serviceDir, 'd');
                daemontoolsService.waitUntil(serviceDir, false, stopTimeout);

                log.debug("Deleting file %s", jarFile.getAbsolutePath());
                deleteFile(jarFile);

                log.debug("Moving file from %s to %s ...", tempFile.getFile().getAbsolutePath(), jarFile.getAbsolutePath(), jarFile.getAbsolutePath());
                move(tempFile.getFile().toPath(), jarFile.toPath());

                log.debug("Post processing jar file %s", jarFile.getAbsolutePath());
                postProcessJarFile(log, jarFile);

            }

            startSent = sent(serviceDir, 'u');
            daemontoolsService.waitUntil(serviceDir, true, startTimeout);

            waitUrl.waitForSuccessResponse();

            aResponse.setStatus(HttpServletResponse.SC_OK);
            log.debug("Last log lines");
            new LastLogLines(logFile, 10).showLastLines(sb);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (e instanceof DeliveredMaybe maybe) {
                stopSent  |= maybe.command == 'd';
                startSent |= maybe.command == 'u';
            }
            log.debug("Error: %s", e);
            LOG.error("Cannot process jar", e);
            if (stopSent && !startSent) {
                bringBackUp(serviceDir, log);
            }
            aResponse.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
            log.debug("Last log lines");
            new LastLogLines(logFile, 50).showLastLines(sb);
        } catch (Error e) { // NOSONAR: out of memory, no thread — still start the service it stopped, then rethrow
            if (stopSent && !startSent) {
                bringBackUp(serviceDir, log);
            }
            throw e;
        }

        aResponse.getWriter().write(sb.toString());
        aResponse.getWriter().flush();
    }

    /**
     * Sends the command; {@code true} once supervise has it. A command that may have been delivered
     * counts as delivered (and still fails the deploy): after a stop that may have happened the
     * service is started again, after a start that may have happened it is not asked twice.
     */
    private boolean sent(File aServiceDir, char aCommand) throws IOException, InterruptedException {
        try {
            daemontoolsService.send(aServiceDir, aCommand);
            return true;
        } catch (com.payneteasy.dcagent.core.daemontools.Supervise.CommandMayBeDelivered e) {
            throw new DeliveredMaybe(aCommand, e);
        }
    }

    /** {@link #sent} for a command that may have been delivered: the caller marks it as sent. */
    private static final class DeliveredMaybe extends IOException {
        final char command;

        DeliveredMaybe(char aCommand, Exception aCause) {
            super(aCause.getMessage(), aCause);
            command = aCommand;
        }
    }

    /**
     * Asks supervise to start the service again after a failed deploy: it starts as soon as the old
     * process has exited, with whatever jar is in place (the old one unless it was already replaced).
     */
    private void bringBackUp(File aServiceDir, ILog aLog) {
        boolean interrupted = Thread.interrupted(); // an interrupted deploy still starts the service again
        try {
            daemontoolsService.sendUninterruptibly(aServiceDir, 'u'); // an interrupt meanwhile is kept, not obeyed
            aLog.debug("Asked service %s to start again after the failed deploy", aServiceDir.getAbsolutePath());
        } catch (Exception e) {
            aLog.debug("Cannot ask service %s to start again: %s", aServiceDir.getAbsolutePath(), e);
            LOG.error("Cannot start service {} again after the failed deploy", aServiceDir.getAbsolutePath(), e);
        } finally {
            if (interrupted) { // a new interrupt during the send is kept by it
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deleteFile(File aFile) {
        if(!aFile.exists()) {
            return;
        }
        LOG.debug("Deleting file {}", aFile.getAbsolutePath());
        if(!aFile.delete()) {
            throw new IllegalStateException("Cannot delete file " + aFile.getAbsolutePath());
        }
    }

    /** The {@code type} the command config must have (a config without type is accepted with a warning). */
    protected abstract TaskType expectedType();

    protected abstract void postProcessJarFile(ILog log, File aWarFile);

    protected abstract File getJarFile(TJarConfig jarConfig);

    private String getDefault(String aValue, String aDefault) {
        return hasText(aValue) ? aValue : aDefault;
    }

}
