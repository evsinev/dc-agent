package com.payneteasy.dcagent.modules.saveartifact;

import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import com.payneteasy.dcagent.util.PathParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
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
        LOG.debug("Processing artifact {} ...", aRequest.getRequestURI());

        PathParameters      parameters = new PathParameters(aRequest.getRequestURI());
        String              name       = parameters.getLastButOne();
        String              version    = parameters.getLast();
        TSaveArtifactConfig config     = configService.getSaveArtifactConfig(name);
        File                file       = new File(config.getDir(), version + "." + config.getExtension());

        checkApiKey.check(aRequest, config);

        try {
            writeFile(file, aRequest.getInputStream());
        } catch (Exception e) {
            aResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            LOG.error("Cannot write file", e);
        }
    }

    private void writeFile(File aFile, ServletInputStream aInputStream) throws IOException {
        try(FileOutputStream out = new FileOutputStream(aFile)) {
            byte[] buf = new byte[4096];
            int count;
            while ( (count = aInputStream.read(buf)) >= 0 ) {
                out.write(buf, 0, count);
            }
        }
    }
}
