package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.util.Strings;

import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.saveartifact.SaveArtifactPath;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;


public class SaveArtifactServlet extends HttpServlet {
    private static final Logger LOG = LoggerFactory.getLogger(SaveArtifactServlet.class);

    private final IConfigService configService;
    private final CheckApiKey    checkApiKey = new CheckApiKey();


    public SaveArtifactServlet(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing artifact {} ...", Strings.forLog(aRequest.getRequestURI()));
        }

        PathParameters      parameters = new PathParameters(aRequest.getRequestURI());
        String              name       = parameters.getLastButOne();
        String              version    = parameters.getLast();
        TSaveArtifactConfig config     = configService.getSaveArtifactConfig(name);

        // nothing touches the disk before the key is checked
        checkApiKey.check(aRequest, config);

        File file = SaveArtifactPath.resolve(config, version, aRequest.getHeader("x-dc-agent-file-extension"));
        SafeFiles.createDirs(file.getParentFile());

        try {
            SaveArtifactPath.write(file, aRequest.getInputStream());
        } catch (Exception e) {
            aResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            LOG.error("Cannot write file {}", Strings.forLog(file.getAbsolutePath()), e);
        }
    }

}
