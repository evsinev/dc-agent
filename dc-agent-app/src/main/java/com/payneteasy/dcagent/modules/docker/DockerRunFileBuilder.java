package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.config.model.docker.*;
import com.payneteasy.dcagent.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.util.Strings;

import java.util.List;

public class DockerRunFileBuilder {

    private final TextLinesBuilder lines = new TextLinesBuilder();

    public void createRunFile(TDocker aService) {
        lines.addLines(
                "#!/usr/bin/env bash"
                , ""
                , "exec 2>&1"
                , ""
                , "docker rm " + aService.getName()
                , ""
                , "exec docker run \\"
                , "  --net=host \\"
                , "  --name=" + aService.getName() + " \\"
        );

        addBoundVariables ( aService.getEnv()      );
        addVolumes        ( aService.getVolumes()  );
        addWorkingDir     ( aService.getContainerWorkingDir() );
        addDockerImage    ( aService.getImage()    );
        addArgs           ( aService.getArgs()     );

    }

    private void addArgs(String[] args) {
        if(args == null || args.length == 0) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(" ");
        for (String arg : args) {
            sb.append(' ');
            boolean containsSpace = arg.contains(" ");
            if(containsSpace) {
                sb.append("\"");
            }
            sb.append(arg);
            if(containsSpace) {
                sb.append("\"");
            }
        }
        lines.addLine(sb.toString());
    }

    private void addWorkingDir(String aWorkingDir) {
        if(Strings.isEmpty(aWorkingDir)) {
            return;
        }
        lines.addLineConcat("  -w ", aWorkingDir, " \\");
    }

    private void addDockerImage(DockerImage aImage) {
        lines.addLineConcat("  ", aImage.getName(), " \\");
    }

    private void addVolumes(List<DockerVolume> aVolumes) {
        if(aVolumes == null) {
            return;
        }
        for (DockerVolume dockerVolume : aVolumes) {
            IVolume volume = dockerVolume.getVolume();
            String readOnlyOption = volume.isReadonly() ? ":ro" : "";
            lines.addLineConcat("  -v ", volume.getSource(), ":", volume.getDestination(), readOnlyOption, " \\");
        }
    }

    private void addBoundVariables(List<EnvVariable> aVariables) {
        for (EnvVariable env : aVariables) {
            lines.addLineConcat("  -e \"", env.getName(), "=", env.getValue(), "\" \\");
        }
    }

    public String buildText() {
        return lines.toString();
    }


}
