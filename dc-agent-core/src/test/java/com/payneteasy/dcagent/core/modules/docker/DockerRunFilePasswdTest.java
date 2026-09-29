package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.DockerImage;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.modules.docker.runtime.ContainerRuntime;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** passwdEntry / tmpfs in the run script; configs without them keep the same script in both modes. */
public class DockerRunFilePasswdTest {

    private static final String TEMPLATE = "$USERNAME:*:$UID:$GID::/tmp:/sbin/nologin";
    private static final File   PASSWD   = new File("/etc/service.d/app-1/container-passwd");

    @Test
    public void podman_gets_its_own_passwd_entry_option_verbatim() {
        String text = run(ContainerRuntime.PODMAN, security().passwdEntry(TEMPLATE).build());

        assertThat(text)
                .contains("  --user 20500:20500 \\\n  --read-only \\\n  --tmpfs /tmp:rw,size=16m \\\n  --passwd-entry '" + TEMPLATE + "' \\\n")
                .doesNotContain("/etc/passwd");
    }

    @Test
    public void docker_mounts_the_generated_file_over_etc_passwd() {
        String text = run(ContainerRuntime.DOCKER, security().passwdEntry(TEMPLATE).build());

        assertThat(text)
                .contains("  --tmpfs /tmp:rw,size=16m \\\n  -v /etc/service.d/app-1/container-passwd:/etc/passwd:ro \\\n")
                .doesNotContain("--passwd-entry");
    }

    @Test
    public void tmpfs_without_options_and_several_entries() {
        String text = run(ContainerRuntime.PODMAN, security().tmpfs(List.of("/tmp", "/var/cache/app:size=8m")).build());

        assertThat(text).contains("  --tmpfs /tmp \\\n  --tmpfs /var/cache/app:size=8m \\\n");
    }

    @Test
    public void a_config_without_the_new_fields_gets_the_same_script_in_both_modes() {
        TSecurityContext security = TSecurityContext.builder().runAsUser(20500).runAsGroup(20500).readOnlyRootFilesystem(true).build();

        String legacy = DockerRunFileBuilder.createRunFileText(docker(security), "/env");

        assertThat(run(ContainerRuntime.PODMAN, security)).isEqualTo(legacy);
        assertThat(run(ContainerRuntime.DOCKER, security)).isEqualTo(legacy);
        assertThat(legacy).doesNotContain("--tmpfs").doesNotContain("passwd");
    }

    private static TSecurityContext.TSecurityContextBuilder security() {
        return TSecurityContext.builder()
                .runAsUser(20500)
                .runAsGroup(20500)
                .readOnlyRootFilesystem(true)
                .tmpfs(List.of("/tmp:rw,size=16m"));
    }

    private static String run(ContainerRuntime aRuntime, TSecurityContext aSecurity) {
        return DockerRunFileBuilder.createRunFileText(docker(aSecurity), "/env", aRuntime, PASSWD);
    }

    private static TDocker docker(TSecurityContext aSecurity) {
        return TDocker.builder()
                .name("app-1")
                .image(DockerImage.builder().name("img:1").build())
                .securityContext(aSecurity)
                .build();
    }
}
