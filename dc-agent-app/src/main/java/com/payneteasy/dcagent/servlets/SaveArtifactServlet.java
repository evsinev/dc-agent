package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import com.payneteasy.dcagent.core.util.PathParameters;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

import static com.payneteasy.dcagent.core.util.Streams.writeFile;
import static com.payneteasy.dcagent.core.util.Strings.hasText;

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
        String              filename   = createFilename(version, config);
        File                file       = createFile(config, filename, aRequest.getHeader("x-dc-agent-file-extension"));

        SafeFiles.createDirs(file.getParentFile());

        checkApiKey.check(aRequest, config);

        try {
            writeFile(file, aRequest.getInputStream());
        } catch (Exception e) {
            aResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            LOG.error("Cannot write file", e);
        }
    }

    @NotNull
    private static File createFile(TSaveArtifactConfig config, String filename, String aFileExtension) {
        String extension = hasText(aFileExtension) ? aFileExtension : config.getExtension();
        return new File(config.getDir(), filename + "." + extension);
    }

    private String createFilename(String version, TSaveArtifactConfig config) {
        if(version.contains("..")) {
            throw new IllegalStateException("Name contains '..' - " + version);
        }
        return hasText(config.getReplaceDirChars())
                ? version.replace(config.getReplaceDirChars(), "/")
                : version;
    }

}
