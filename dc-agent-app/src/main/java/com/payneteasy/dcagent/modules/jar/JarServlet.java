package com.payneteasy.dcagent.modules.jar;

import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.model.TJarConfig;

import java.io.File;

public class JarServlet extends AbstractJarServlet {

    public JarServlet(IConfigService configService) {
        super(configService);
    }

    @Override
    protected File getJarFile(TJarConfig jarConfig) {
        return new File(jarConfig.getJarFilename());
    }

    @Override
    protected void processJarFile(ILog log, File aWarFile) {

    }
}
