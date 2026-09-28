package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.DockerDirectories;
import com.payneteasy.dcagent.core.config.model.docker.DockerImage;
import com.payneteasy.dcagent.core.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.core.config.model.docker.EnvType;
import com.payneteasy.dcagent.core.config.model.docker.EnvVariable;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityCapabilities;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.volumes.DirectoryOrCreateVolume;
import com.payneteasy.dcagent.core.config.model.docker.volumes.LinkToHostDirectoryVolume;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DockerRunFileSecurityTest {

    /**
     * Captured from the builder before runAsUser support. A config without the new fields must
     * produce exactly this text, otherwise DOCKER_CHECK shows a diff on every existing service.
     */
    private static final String LEGACY_RUN = ""
            + "#!/usr/bin/env bash\n"
            + "\n"
            + "exec 2>&1\n"
            + "\n"
            + "docker rm app-1\n"
            + "\n"
            + "exec \\\n"
            + "  /usr/bin/envdir /env \\\n"
            + "  docker run \\\n"
            + "  --rm \\\n"
            + "  --net=host \\\n"
            + "  --log-driver none \\\n"
            + "  --name=app-1 \\\n"
            + "  --cap-add IPC_LOCK \\\n"
            + "  --cap-drop NET_RAW \\\n"
            + "--privileged \\\n"
            + "  -e A=\"1\" \\\n"
            + "  -e B \\\n"
            + "  -v /opt/app/data:\"/data\" \\\n"
            + "  -v /opt/x:\"/x\":ro \\\n"
            + "  -w /app \\\n"
            + "  img:1 \\\n"
            + "  java -jar \"my app.jar\"\n";

    @Test
    public void legacy_config_is_byte_identical() {
        assertThat(run(legacySecurity().build())).isEqualTo(LEGACY_RUN);
    }

    @Test
    public void user_and_group() {
        String text = run(legacySecurity().runAsUser(1001).runAsGroup(1002).build());

        assertThat(text).isEqualTo(LEGACY_RUN.replace(
                "  --name=app-1 \\\n",
                "  --name=app-1 \\\n  --user 1001:1002 \\\n"
        ));
    }

    /** Without a group docker takes the primary group of the user from the image, or GID 0. */
    @Test
    public void user_without_group() {
        assertThat(run(legacySecurity().runAsUser(0).build())).contains("  --user 0 \\\n");
    }

    @Test
    public void group_without_user_is_rejected() {
        assertThatThrownBy(() -> run(legacySecurity().runAsGroup(1001).build()))
                .hasMessageContaining("securityContext.runAsGroup")
                .hasMessageContaining("runAsUser");
    }

    @Test
    public void read_only_and_no_new_privileges() {
        String text = run(TSecurityContext.builder()
                .runAsUser(1001)
                .runAsGroup(1001)
                .readOnlyRootFilesystem(true)
                .allowPrivilegeEscalation(false)
                .capabilities(TSecurityCapabilities.builder().drop(List.of("ALL")).build())
                .build());

        assertThat(text).contains(""
                + "  --name=app-1 \\\n"
                + "  --user 1001:1001 \\\n"
                + "  --read-only \\\n"
                + "  --security-opt no-new-privileges \\\n"
                + "  --cap-drop ALL \\\n"
        );
    }

    @Test
    public void explicit_defaults_add_nothing() {
        String text = run(legacySecurity()
                .readOnlyRootFilesystem(false)
                .allowPrivilegeEscalation(true)
                .build());

        assertThat(text).isEqualTo(LEGACY_RUN);
    }

    private static TSecurityContext.TSecurityContextBuilder legacySecurity() {
        return TSecurityContext.builder()
                .privileged(true)
                .capabilities(TSecurityCapabilities.builder()
                        .add(List.of("IPC_LOCK"))
                        .drop(List.of("NET_RAW"))
                        .build());
    }

    private static String run(TSecurityContext aSecurity) {
        return DockerRunFileBuilder.createRunFileText(TDocker.builder()
                .name("app-1")
                .image(DockerImage.builder().name("img:1").build())
                .env(List.of(
                        EnvVariable.builder().name("A").value("1").build(),
                        EnvVariable.builder().name("B").type(EnvType.ENV_DIR).build()
                ))
                .volumes(List.of(
                        DockerVolume.builder().directoryOrCreate(DirectoryOrCreateVolume.builder()
                                .source("/opt/app/data").destination("/data").build()).build(),
                        DockerVolume.builder().linkToHostDirectory(LinkToHostDirectoryVolume.builder()
                                .source("/opt/x").destination("/x").readonly(true).build()).build()
                ))
                .directories(DockerDirectories.builder().containerWorkingDir("/app").build())
                .args(new String[]{"java", "-jar", "my app.jar"})
                .securityContext(aSecurity)
                .build(), "/env");
    }
}
