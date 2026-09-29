package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.*;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.modules.docker.runtime.ContainerRuntime;
import com.payneteasy.dcagent.core.modules.docker.runtime.TmpfsMount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static com.payneteasy.dcagent.core.util.SaveList.safeList;
import static com.payneteasy.dcagent.core.util.Strings.isEmpty;

public class DockerRunFileBuilder {

    private static final Logger LOG = LoggerFactory.getLogger( DockerRunFileBuilder.class );
    private static final String LINE_END_NEXT = " \\";

    private final TextLinesBuilder lines = new TextLinesBuilder();

    DockerRunFileBuilder() {
    }

    public static String createRunFileText(TDocker aService, String aEnvDir) {
        return createRunFileText(aService, aEnvDir, ContainerRuntime.PODMAN, null);
    }

    /**
     * @param aContainerPasswd the generated passwd file (docker runtime with passwdEntry), else ignored
     */
    public static String createRunFileText(TDocker aService, String aEnvDir, ContainerRuntime aRuntime, File aContainerPasswd) {
        DockerRunFileBuilder builder = new DockerRunFileBuilder();
        builder.runtime         = aRuntime;
        builder.containerPasswd = aContainerPasswd;
        return builder.createRunFileTextInternal(aService, aEnvDir);
    }

    private ContainerRuntime runtime = ContainerRuntime.PODMAN;
    private File             containerPasswd;

    String createRunFileTextInternal(TDocker aService, String aEnvDir) {
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

        addUser           ( aService.getSecurityContext() );
        addReadOnlyRoot   ( aService.getSecurityContext() );
        addTmpfs          ( aService.getSecurityContext() );
        addPasswdEntry    ( aService.getSecurityContext() );
        addNoNewPrivileges( aService.getSecurityContext() );
        addCapabilities   ( aService.getSecurityContext() );
        addPrivileged     ( aService.getSecurityContext() );
        addBoundVariables ( aService.getEnv()      );
        addVolumes        ( aService.getVolumes()  );
        addWorkingDir     ( aService.getDirectories() );
        addDockerImage    ( aService.getImage()    );
        addArgs           ( aService.getArgs()     );

        return buildText();
    }

    /**
     * {@code --user U} takes the primary group of U from the image's /etc/passwd (GID 0 if the
     * image has no entry); {@code --user U:G} uses exactly G and drops the image's supplementary
     * groups of U.
     */
    private void addUser(TSecurityContext aContext) {
        if (aContext == null) {
            return;
        }
        Integer user  = aContext.getRunAsUser();
        Integer group = aContext.getRunAsGroup();
        if (user == null) {
            if (group != null) {
                throw new IllegalStateException("securityContext.runAsGroup is set without runAsUser: docker does not accept --user :" + group);
            }
            return;
        }
        String value = group == null ? String.valueOf(user) : user + ":" + group;
        lines.addLineConcat("  --user ", value, LINE_END_NEXT);
    }

    private void addReadOnlyRoot(TSecurityContext aContext) {
        if (aContext == null || !Boolean.TRUE.equals(aContext.getReadOnlyRootFilesystem())) {
            return;
        }
        lines.addLineConcat("  --read-only", LINE_END_NEXT);
    }

    private void addTmpfs(TSecurityContext aContext) {
        if (aContext == null) {
            return;
        }
        for (String spec : safeList(aContext.getTmpfs())) {
            lines.addLineConcat("  --tmpfs ", TmpfsMount.parse(spec).argument(), LINE_END_NEXT);
        }
    }

    /**
     * podman: its own {@code --passwd-entry} (the image's line stays when the uid is in the image);
     * docker has no such flag: a generated file (root + the entry) is mounted over /etc/passwd.
     * The template is validated to a quote-free character set (ContainerMountsCheck).
     */
    private void addPasswdEntry(TSecurityContext aContext) {
        if (aContext == null || aContext.getPasswdEntry() == null) {
            return;
        }
        if (runtime == ContainerRuntime.PODMAN) {
            lines.addLineConcat("  --passwd-entry '", aContext.getPasswdEntry(), "'", LINE_END_NEXT);
            return;
        }
        if (containerPasswd == null) {
            throw new IllegalStateException("No container passwd file for the docker runtime");
        }
        lines.addLineConcat("  -v ", containerPasswd.getAbsolutePath(), ":/etc/passwd:ro", LINE_END_NEXT);
    }

    private void addNoNewPrivileges(TSecurityContext aContext) {
        if (aContext == null || !Boolean.FALSE.equals(aContext.getAllowPrivilegeEscalation())) {
            return;
        }
        lines.addLineConcat("  --security-opt no-new-privileges", LINE_END_NEXT);
    }

    private void addPrivileged(TSecurityContext aContext) {
        if (aContext == null || aContext.getPrivileged() == null || !aContext.getPrivileged()) {
            return;
        }
        lines.addLineConcat("--privileged", LINE_END_NEXT);
    }

    private void addCapabilities(TSecurityContext aContext) {
        if (aContext == null || aContext.getCapabilities() == null) {
            return;
        }

        for (String add : safeList(aContext.getCapabilities().getAdd())) {
            lines.addLineConcat("  --cap-add ", add, LINE_END_NEXT);
        }

        for (String drop : safeList(aContext.getCapabilities().getDrop())) {
            lines.addLineConcat("  --cap-drop ", drop, LINE_END_NEXT);
        }
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
