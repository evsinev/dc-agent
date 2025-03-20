package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.modules.jar.ILog;
import com.payneteasy.dcagent.core.modules.zipachive.ZipFileExtractor;
import com.payneteasy.dcagent.core.util.DeleteDirRecursively;

import java.io.File;
import java.io.IOException;

public class NodeServlet extends AbstractJarServlet {

    private final ZipFileExtractor zipExtractor = new ZipFileExtractor();

    public NodeServlet(IConfigService configService, DaemontoolsServiceImpl aDaemontoolsService) {
        super(configService, aDaemontoolsService);
    }


    @Override
    protected void postProcessJarFile(ILog log, File aJarFile) {
        File targetDir = aJarFile.getParentFile();

        DeleteDirRecursively deleteDirRecursively = new DeleteDirRecursively(targetDir);
        deleteDirRecursively.deleteDirIfExists(new File(targetDir, "app"));
        deleteDirRecursively.deleteDirIfExists(new File(targetDir, "target"));

        try {
            zipExtractor.extractZip(aJarFile, targetDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot extract zip file " + aJarFile.getAbsolutePath(), e);
        }
    }

    @Override
    protected File getJarFile(TJarConfig jarConfig) {
        return new File(jarConfig.getJarFilename());
    }
}
