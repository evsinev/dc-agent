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
import java.util.Date;

import static com.payneteasy.dcagent.core.util.Strings.hasText;
import static com.payneteasy.dcagent.core.util.Units.parseDuration;
import static java.nio.file.Files.move;

public abstract class AbstractJarServlet extends HttpServlet {

    private final Logger LOG = LoggerFactory.getLogger(getClass());

    private final CommandAuth commandAuth = new CommandAuth();

    private final IConfigService         configService;
    private final DaemontoolsServiceImpl daemontoolsService;

    public AbstractJarServlet(IConfigService configService, DaemontoolsServiceImpl daemontoolsService) {
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

        try {
            try (TempFile tempFile = new TempFile(name, "jar")) {
                log.debug("Saving file to temp location %s ...", tempFile.getFile().getAbsolutePath());
                tempFile.writeFromInputStream(aRequest.getInputStream());

                daemontoolsService.stopService(serviceDir, parseDuration(jarConfig.getServiceStopTimeout(), "30s"));

                log.debug("Deleting file %s", jarFile.getAbsolutePath());
                deleteFile(jarFile);

                log.debug("Moving file from %s to %s ...", tempFile.getFile().getAbsolutePath(), jarFile.getAbsolutePath(), jarFile.getAbsolutePath());
                move(tempFile.getFile().toPath(), jarFile.toPath());

                log.debug("Post processing jar file %s", jarFile.getAbsolutePath());
                postProcessJarFile(log, jarFile);

            }

            daemontoolsService.startService(serviceDir, parseDuration(jarConfig.getServiceStopTimeout(), "10s"));

            new WaitUrlCommand()
                    .setWaitUrl             ( jarConfig.getWaitUrl() )
                    .setConnectTimeout      ( parseDuration( jarConfig.getWaitConnectTimeout(), "30s"))
                    .setReadTimeout         ( parseDuration( jarConfig.getWaitReadTimeout()   , "30s"))
                    .setWaitDuration        ( parseDuration( jarConfig.getWaitDuration()      , "3m"))
                    .setLog                 ( log )
                    .setCheck               ( new ServiceLoopDetector(daemontoolsService, serviceDir))
                    .waitForSuccessResponse();

            aResponse.setStatus(HttpServletResponse.SC_OK);
            log.debug("Last log lines");
            new LastLogLines(logFile, 10).showLastLines(sb);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Error: %s", e);
            LOG.error("Cannot process jar", e);
            aResponse.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
            log.debug("Last log lines");
            new LastLogLines(logFile, 50).showLastLines(sb);
        }

        aResponse.getWriter().write(sb.toString());
        aResponse.getWriter().flush();
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
