package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.modules.jar.ILog;
import com.payneteasy.dcagent.core.util.DeleteDirRecursively;

import java.io.File;

public class WarServlet extends AbstractJarServlet {

    public WarServlet(IConfigService configService) {
        super(configService);
    }

    @Override
    protected File getJarFile(TJarConfig jarConfig) {
        return new File(jarConfig.getWarFilename());
    }

    @Override
    protected void postProcessJarFile(ILog log, File aWarFile) {
        String warFilename = aWarFile.getAbsolutePath();
        File   warDir      = new File(warFilename.substring(0, warFilename.length() - 4));

        log.debug("War dir  %s", warDir.getAbsolutePath());

        new DeleteDirRecursively(aWarFile.getParentFile())
                .deleteDirIfExists(warDir);

//        if(aWarFile.exists() && !aWarFile.delete()) {
//            throw new IllegalStateException("Cannot delete file " + aWarFile.getAbsolutePath());
//        }

    }
}
