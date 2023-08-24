package com.payneteasy.dcagent.servlets;


import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.modules.zipachive.ZipFileExtractor;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

public class ZipDirsServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ZipDirsServlet.class);

    private static final Set<Character> ALLOWED_CHARS = createAllowedChars();

    private final IConfigService configService;
    private final CheckApiKey    checkApiKey = new CheckApiKey();

    public ZipDirsServlet(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
        LOG.debug("Processing {} ...", aRequest.getRequestURI());

        PathParameters   parameters       = new PathParameters(aRequest.getRequestURI());
        String           name             = extractName(parameters);
        TZipDirsConfig   zipDirsConfig    = configService.getZipDirsConfig(name);
        ZipFileExtractor zipFileExtractor = new ZipFileExtractor();
        File             targetDir        = createTargetDir(zipDirsConfig.getDir(), parameters);

        checkApiKey.check(aRequest, zipDirsConfig);

        try (TempFile tempFile = new TempFile(name, "zip")) {
            tempFile.writeFromInputStream(aRequest.getInputStream());
            zipFileExtractor.extractZip(tempFile.getFile(), targetDir);
        }

    }


    private File createTargetDir(String aDir, PathParameters aParameters) {
        File dir = new File(aDir);
        SegmentState state = SegmentState.SEARCHING;
        for (String segment : aParameters.getParams()) {
            if(segment.equals("zip-dirs")) {
                state = SegmentState.ZIP_DIR;
                continue;
            }

            if(state == SegmentState.ZIP_DIR) {
                state = SegmentState.FOUND;
                continue;
            }

            if(state == SegmentState.FOUND) {
                dir = new File(dir, sanitize(segment));
            }
        }

        if(state != SegmentState.FOUND) {
            throw new IllegalStateException("Bad path " + aParameters.getParams() + ", stage is " + state);
        }

        if(!dir.exists()) {
            LOG.info("Creating dir {} ...", dir.getAbsolutePath());
            if(!dir.mkdirs()) {
                throw new IllegalStateException("Cannot create dir " + dir.getAbsolutePath());
            }
        }
        return dir;
    }

    private String extractName(PathParameters aParameters) {
        SegmentState state = SegmentState.SEARCHING;

        for (String segment : aParameters.getParams()) {
            if(segment.equals("zip-dirs")) {
                state = SegmentState.ZIP_DIR;
                continue;
            }

            if(state == SegmentState.ZIP_DIR) {
                return segment;
            }

        }

        throw new IllegalStateException("Bad path " + aParameters.getParams() + ", stage is " + state);
    }

    private String sanitize(String aName) {
        for (char c : aName.toCharArray()) {
            if(!ALLOWED_CHARS.contains(c)) {
                throw new IllegalStateException("Bad char " + c + " in " + aName);
            }
        }
        return aName;
    }

    private enum SegmentState {
        SEARCHING, ZIP_DIR, FOUND
    }

    private static Set<Character> createAllowedChars() {
        Set<Character> set = new TreeSet<>();
        for (char character : "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_".toCharArray()) {
            set.add(character);
        }
        return set;
    }

}