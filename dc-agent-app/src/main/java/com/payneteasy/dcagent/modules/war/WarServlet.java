package com.payneteasy.dcagent.modules.war;

import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.model.TWarConfig;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import com.payneteasy.dcagent.modules.zipachive.TempFile;
import com.payneteasy.dcagent.util.DeleteDirRecursively;
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

public class WarServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(WarServlet.class);

    private final IConfigService  configService;
    private final CheckApiKey     checkApiKey    = new CheckApiKey();

    public WarServlet(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {

        PathParameters         parameters         = new PathParameters(aRequest.getRequestURI());
        String                 name               = parameters.getLast();
        TWarConfig             warConfig          = configService.getWarConfig(name);
        File                   serviceDir         = new File(warConfig.getServiceDir());
        File                   warFile            = new File(warConfig.getWarFilename());
        File                   warDir             = new File(warConfig.getWarFilename().substring(0, warConfig.getWarFilename().length() - 4));
        File                   logFile            = new File(warConfig.getServiceLogFile());

        checkApiKey.check(aRequest, warConfig);

        StringBuffer  sb = new StringBuffer();
        ILog log = (aFormat, args) -> {
            String message = String.format(aFormat, args);
            sb.append('\n').append(new Date()).append(' ').append(message);
            LOG.debug("{}: {}", name, message);
        };

        log.debug("War file %s", warFile.getAbsolutePath());
        log.debug("War dir  %s", warDir.getAbsolutePath());
        log.debug("Log file %s", logFile.getAbsolutePath());

        DaemontoolsServiceImpl daemontoolsService = new DaemontoolsServiceImpl(
                  getDefault(warConfig.getSvcCommand(), "/usr/bin/svc")
                , getDefault(warConfig.getSvstatCommand(), "/usr/bin/svstat")
                , log);

        log.debug("Processing %s...", name);

        try {
            try (TempFile tempFile = new TempFile(name, "war")) {
                log.debug("Saving file to %s ...", tempFile.getFile().getAbsolutePath());
                tempFile.writeFromInputStream(aRequest.getInputStream());

                daemontoolsService.svc(serviceDir, parseDuration(warConfig.getServiceStopTimeout(), "30s"));

                new DeleteDirRecursively(warFile.getParentFile()).deleteDirIfExists(warDir);

                if(warFile.exists() && !warFile.delete()) {
                    throw new IllegalStateException("Cannot delete file " + warFile.getAbsolutePath());
                }

                move(tempFile.getFile().toPath(), new File(warConfig.getWarFilename()).toPath());

            }

            daemontoolsService.startService(serviceDir, parseDuration(warConfig.getServiceStopTimeout(), "10s"));

            new WaitUrlCommand()
                    .setWaitUrl             ( warConfig.getWaitUrl() )
                    .setConnectTimeout      ( parseDuration( warConfig.getWaitConnectTimeout(), "30s"))
                    .setReadTimeout         ( parseDuration( warConfig.getWaitReadTimeout()   , "30s"))
                    .setWaitDuration        ( parseDuration( warConfig.getWaitDuration()      , "3m"))
                    .setLog                 ( log )
                    .setCheck               ( new ServiceLoopDetector(daemontoolsService, serviceDir))
                    .waitForSuccessResponse();

            aResponse.setStatus(HttpServletResponse.SC_OK);
            log.debug("Last log lines");
            new LastLogLines(logFile, 10).showLastLines(sb);

        } catch (Exception e) {
            log.debug("Error: %s", e);
            LOG.error("Cannot process war", e);
            aResponse.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
            log.debug("Last log lines");
            new LastLogLines(logFile, 50).showLastLines(sb);
        }

        aResponse.getWriter().write(sb.toString());
        aResponse.getWriter().flush();
    }

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
