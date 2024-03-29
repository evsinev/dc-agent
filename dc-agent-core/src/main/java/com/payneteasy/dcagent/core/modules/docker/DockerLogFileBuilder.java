package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;

public class DockerLogFileBuilder {

    private DockerLogFileBuilder() {
    }

    public static String createLogFileText(ServicesLogDir aLogDir, TDocker aService) {
        TextLinesBuilder lines = new TextLinesBuilder();
        lines.addLines(
                "#!/usr/bin/env bash"
                , ""
                , "exec setuidgid " + aService.getOwner().getUser() + " multilog s5242880 " + aLogDir.getServiceLogDir(aService.getName())
        );
        return lines.toString();
    }

}
