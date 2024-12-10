package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.*;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.payneteasy.dcagent.core.util.Strings.isEmpty;

public class DockerRunFileBuilder {

    private static final Logger LOG = LoggerFactory.getLogger( DockerRunFileBuilder.class );

    private final TextLinesBuilder lines = new TextLinesBuilder();

    private DockerRunFileBuilder() {
    }

    public static String createRunFileText(TDocker aService, String aEnvDir) {
        return new DockerRunFileBuilder().createRunFileTextInternal(aService, aEnvDir);
    }


    private String createRunFileTextInternal(TDocker aService, String aEnvDir) {
        lines.addLines(
                "#!/usr/bin/env bash"
                , ""
                , "exec 2>&1"
                , ""
                , "docker rm " + aService.getName()
                , ""
                , "exec \\"
                , "  /usr/bin/envdir " + aEnvDir + " \\"
                , "  docker run \\"
                , "  --rm \\"
                , "  --net=host \\"
                , "  --log-driver none \\"
                , "  --name=" + aService.getName() + " \\"
        );

        addBoundVariables ( aService.getEnv()      );
        addVolumes        ( aService.getVolumes()  );
        addWorkingDir     ( aService.getDirectories() );
        addDockerImage    ( aService.getImage()    );
        addArgs           ( aService.getArgs()     );

        return buildText();
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

    private void addWorkingDir(DockerDirectories aDirectories) {
        if(aDirectories == null || isEmpty(aDirectories.getContainerWorkingDir())) {
            return;
        }
        lines.addLineConcat("  -w ", aDirectories.getContainerWorkingDir(), " \\");
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
            lines.addLineConcat("  -v ", volume.getSource(), ":\"", volume.getDestination(), "\"", readOnlyOption, " \\");
        }
    }

    private void addBoundVariables(List<EnvVariable> aVariables) {
        if(aVariables == null) {
            LOG.warn("No any bound variables");
            return;
        }
        for (EnvVariable env : aVariables) {
            if (env.getType() == EnvType.ENV_DIR) {
                lines.addLineConcat("  -e ", env.getName(), " \\");
            } else {
                lines.addLineConcat("  -e ", env.getName(), "=\"", env.getValue(), "\" \\");
            }
        }
    }

    public String buildText() {
        return lines.toString();
    }


}
