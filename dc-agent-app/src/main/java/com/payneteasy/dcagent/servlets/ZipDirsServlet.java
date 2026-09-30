package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.util.Strings;


import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.modules.zipachive.ZipFileExtractor;
import com.payneteasy.dcagent.core.util.PathParameters;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.jetty.CommandAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class ZipDirsServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ZipDirsServlet.class);

    private static final Set<Character> ALLOWED_CHARS = createAllowedChars();

    private final IConfigService configService;
    private final CommandAuth    commandAuth = new CommandAuth();

    public ZipDirsServlet(IConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
        PathParameters parameters = new PathParameters(aRequest.getRequestURI());
        List<String>   segments   = parameters.getParams();
        int            nameIndex  = segments.indexOf("zip-dirs") + 1;
        String         name       = nameIndex > 0 && nameIndex < segments.size() ? segments.get(nameIndex) : null;
        // nothing — body, mkdirs, log of the request — before the command is authorized
        TZipDirsConfig zipDirsConfig = commandAuth.authorize(aRequest, name, TaskType.ZIP_DIRS, configService::getZipDirsConfig);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing {} ...", Strings.forLog(aRequest.getRequestURI()));
        }

        ZipFileExtractor zipFileExtractor = new ZipFileExtractor();
        File             targetDir        = createTargetDir(zipDirsConfig.getDir(), segments.subList(nameIndex + 1, segments.size()));

        try (TempFile tempFile = new TempFile(name, "zip")) {
            tempFile.writeFromInputStream(aRequest.getInputStream());
            zipFileExtractor.extractZip(tempFile.getFile(), targetDir, zipDirsConfig.isDelete());
        }

    }

    /**
     * {@code dir} itself without a sub-path; otherwise {@code dir/a/b}, canonically inside {@code dir}
     * (no {@code .}/{@code ..} segments, no link inside {@code dir} leading out), created if missing.
     */
    private File createTargetDir(String aDir, List<String> aSubSegments) {
        File dir = new File(aDir);
        if (aSubSegments.isEmpty()) {
            return dir;
        }

        StringBuilder subPath = new StringBuilder();
        for (String segment : aSubSegments) {
            if (subPath.length() > 0) {
                subPath.append('/');
            }
            subPath.append(sanitize(segment));
        }

        File target = SafeFiles.createFileGuarded(dir, subPath.toString());
        if (!target.exists()) {
            LOG.info("Creating dir {} ...", Strings.forLog(target.getAbsolutePath()));
            if (!target.mkdirs()) {
                throw new IllegalStateException("Cannot create dir " + target.getAbsolutePath());
            }
        }
        return target;
    }

    private String sanitize(String aName) {
        if (".".equals(aName) || "..".equals(aName)) {
            throw new IllegalArgumentException("Segment " + aName + " is not allowed in the zip-dirs path");
        }
        for (char c : aName.toCharArray()) {
            if(!ALLOWED_CHARS.contains(c)) {
                throw new IllegalArgumentException("Bad char " + Strings.forLog(String.valueOf(c)) + " in " + Strings.forLog(aName));
            }
        }
        return aName;
    }

    private static Set<Character> createAllowedChars() {
        Set<Character> set = new TreeSet<>();
        for (char character : "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_".toCharArray()) {
            set.add(character);
        }
        return set;
    }

}