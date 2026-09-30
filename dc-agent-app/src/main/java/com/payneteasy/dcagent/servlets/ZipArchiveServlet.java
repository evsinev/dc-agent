package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.util.Strings;


import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.jetty.CommandAuth;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.modules.zipachive.ZipFileExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

public class ZipArchiveServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ZipArchiveServlet.class);

    private final IConfigService configService;
    private final CommandAuth    commandAuth = new CommandAuth();

    public ZipArchiveServlet(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
        PathParameters    parameters = new PathParameters(aRequest.getRequestURI());
        String            name       = parameters.getLast();
        // nothing — body, disk, log of the request — before the command is authorized
        TZipArchiveConfig zipConfig  = commandAuth.authorize(aRequest, name, TaskType.ZIP_ARCHIVE, configService::getZipArchiveConfig);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing {} ...", Strings.forLog(aRequest.getRequestURI()));
        }

        ZipFileExtractor  zipFileExtractor = new ZipFileExtractor();
        File              targetDir        = new File(zipConfig.getDir());

        try (TempFile tempFile = new TempFile(name, "zip")) {
            tempFile.writeFromInputStream(aRequest.getInputStream());
            zipFileExtractor.extractZip(tempFile.getFile(), targetDir);
        }

    }

}