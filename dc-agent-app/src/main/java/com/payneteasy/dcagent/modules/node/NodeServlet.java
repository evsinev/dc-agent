package com.payneteasy.dcagent.modules.node;

import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.model.TJarConfig;
import com.payneteasy.dcagent.modules.jar.AbstractJarServlet;
import com.payneteasy.dcagent.modules.jar.ILog;
import com.payneteasy.dcagent.modules.zipachive.ZipFileExtractor;

import java.io.File;
import java.io.IOException;

public class NodeServlet extends AbstractJarServlet {

    private final ZipFileExtractor zipExtractor = new ZipFileExtractor();

    public NodeServlet(IConfigService configService) {
        super(configService);
    }


    @Override
    protected void postProcessJarFile(ILog log, File aJarFile) {
        try {
            zipExtractor.extractZip(aJarFile, aJarFile.getParentFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot extract zip file " + aJarFile.getAbsolutePath(), e);
        }
    }

    @Override
    protected File getJarFile(TJarConfig jarConfig) {
        return new File(jarConfig.getJarFilename());
    }
}
