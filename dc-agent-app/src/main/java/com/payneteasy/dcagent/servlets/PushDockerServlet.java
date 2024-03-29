package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import com.payneteasy.dcagent.core.modules.docker.ActionLoggerImpl;
import com.payneteasy.dcagent.core.modules.docker.PushDockerAction;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.util.PathParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;

public class PushDockerServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PushDockerServlet.class);

    private final IConfigService        configService;
    private final CheckApiKey           checkApiKey = new CheckApiKey();
    private final TempDir               tempDir;
    private final ServicesDefinitionDir servicesDefinitionDir;
    private final ServicesLogDir        servicesLogDir;
    private final IFileSystemFactory    fileSystemFactory;

    public PushDockerServlet(IConfigService configService, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IFileSystemFactory fileSystemFactory) {
        this.configService         = configService;
        this.tempDir               = tempDir;
        this.servicesDefinitionDir = servicesDefinitionDir;
        this.servicesLogDir        = servicesLogDir;
        this.fileSystemFactory     = fileSystemFactory;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
        LOG.debug("Processing push service {} ...", aRequest.getRequestURI());

        PathParameters parameters = new PathParameters(aRequest.getRequestURI());
        String        name   = parameters.getLast();
        TDockerConfig config = configService.getServiceConfig("dc-docker");

        checkApiKey.check(aRequest, config);

        ActionLoggerImpl logger = new ActionLoggerImpl();
        try {
            PushDockerAction action = new PushDockerAction(name, tempDir, servicesDefinitionDir, servicesLogDir, logger, fileSystemFactory);
            action.pushService(aRequest.getInputStream());
            aResponse.setContentType("text/plain; charset=utf-8");
            aResponse.setCharacterEncoding("utf-8");
            aResponse.getWriter().println(logger.buildText());

        } catch (Exception e) {
            String errorId = UUID.randomUUID().toString();
            LOG.error("Cannot push docker, errorId = {}", errorId, e);
            displayError(aResponse, errorId, e, logger);
        }
    }

    private void displayError(HttpServletResponse aResponse, String aErrorId, Exception e, ActionLoggerImpl logger) throws IOException {
        aResponse.setStatus(500);
        aResponse.setContentType("text/plain; charset=utf-8");
        aResponse.setCharacterEncoding("utf-8");
        PrintWriter writer = aResponse.getWriter();
        writer.println(logger.buildText());
        writer.println("ErrorId = " + aErrorId);
        writer.println("Error list:");
        Throwable exception = e;
        for(int i=0; i<50 && exception != null; i++) {
            writer.printf("%d. %s\n", i + 1, exception.getMessage());
            exception = exception.getCause();
        }
    }

}
