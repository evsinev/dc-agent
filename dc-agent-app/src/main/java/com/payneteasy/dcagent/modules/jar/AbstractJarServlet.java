package com.payneteasy.dcagent.modules.jar;

import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.model.TJarConfig;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import com.payneteasy.dcagent.modules.zipachive.TempFile;
import com.payneteasy.dcagent.util.PathParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Date;

import static com.payneteasy.dcagent.util.Strings.hasText;
import static java.nio.file.Files.move;

public abstract class AbstractJarServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractJarServlet.class);

    private final IConfigService  configService;
    private final CheckApiKey     checkApiKey    = new CheckApiKey();

    public AbstractJarServlet(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {

        PathParameters parameters = new PathParameters(aRequest.getRequestURI());
        String         name       = parameters.getLast();
        TJarConfig     jarConfig  = configService.getJarConfig(name);
        File           serviceDir = new File(jarConfig.getServiceDir());
        File           jarFile    = getJarFile(jarConfig);
        File           logFile    = new File(jarConfig.getServiceLogFile());

        checkApiKey.check(aRequest, jarConfig);

        StringBuffer  sb = new StringBuffer();
        ILog log = (aFormat, args) -> {
            String message = String.format(aFormat, args);
            sb.append('\n').append(new Date()).append(' ').append(message);
            LOG.debug("{}: {}", name, message);
        };

        log.debug("Jar file %s", jarFile.getAbsolutePath());
        log.debug("Log file %s", logFile.getAbsolutePath());

        DaemontoolsServiceImpl daemontoolsService = new DaemontoolsServiceImpl(
                  getDefault(jarConfig.getSvcCommand(), "/usr/bin/svc")
                , getDefault(jarConfig.getSvstatCommand(), "/usr/bin/svstat")
                , log);

        log.debug("Processing %s...", name);

        try {
            try (TempFile tempFile = new TempFile(name, "jar")) {
                log.debug("Saving file to %s ...", tempFile.getFile().getAbsolutePath());
                tempFile.writeFromInputStream(aRequest.getInputStream());

                daemontoolsService.svc(serviceDir, parseDuration(jarConfig.getServiceStopTimeout(), "30s"));

                move(tempFile.getFile().toPath(), getJarFile(jarConfig).toPath());

                processJarFile(log, jarFile);

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
            log.debug("Error: %s", e);
            LOG.error("Cannot process jar", e);
            aResponse.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
            log.debug("Last log lines");
            new LastLogLines(logFile, 50).showLastLines(sb);
        }

        aResponse.getWriter().write(sb.toString());
        aResponse.getWriter().flush();
    }

    protected abstract void processJarFile(ILog log, File aWarFile);

    protected abstract File getJarFile(TJarConfig jarConfig);

    private String getDefault(String aValue, String aDefault) {
        return hasText(aValue) ? aValue : aDefault;
    }


    private Duration parseDuration(String aValue, String aDefault) {
        String text = hasText(aValue) ? aValue : aDefault;
        text = text.toUpperCase();
        if(!text.startsWith("PT")) {
            text = "PT" + text;
        }
        return Duration.parse(text);
    }
}
