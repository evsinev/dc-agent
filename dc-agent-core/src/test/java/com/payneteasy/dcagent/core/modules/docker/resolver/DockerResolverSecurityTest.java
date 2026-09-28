package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.security.TIdRef;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.config.model.docker.volumes.DirectoryOrCreateVolume;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Config checks of round 3: every error is raised before the first change on the file system.
 */
public class DockerResolverSecurityTest {

    private static final String HEADER = ""
            + "name: app\n"
            + "directories:\n"
            + "  sourceBaseDir: /opt/app\n"
            + "  destinationBaseDir: /opt/app\n";

    private static final String STATE_VOLUME = ""
            + "  - directoryOrCreate:\n"
            + "      destination: ./state\n";

    private final RecordingFileSystem fileSystem = new RecordingFileSystem();
    private final List<String>        messages   = new ArrayList<>();
    private final IActionLogger       logger     = (aPattern, args) -> messages.add(aPattern);

    @Test
    public void resolves_owner_and_mode() {
        TDocker docker = resolve(""
                + "securityContext:\n"
                + "  runAsUser: 1001\n"
                + "  runAsGroup: 1002\n"
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: /state\n"
                + "      owner: runAs\n"
                + "      mode: \"0770\"\n");

        DirectoryOrCreateVolume volume = docker.getVolumes().get(0).getDirectoryOrCreate();
        assertThat(volume.getOwner()).isEqualTo(TVolumeOwner.builder()
                .user  ( TIdRef.builder().id(1001).build() )
                .group ( TIdRef.builder().id(1002).build() )
                .build());
        assertThat(volume.getMode()).isEqualTo("0770");
        assertThat(messages).isEmpty();
    }

    @Test
    public void owner_on_other_volume_type_is_rejected_before_any_change() {
        assertRejectedBeforeChanges(""
                + "volumes:\n"
                + STATE_VOLUME
                + "  - linkToHostDirectory:\n"
                + "      source: /opt/templates\n"
                + "      destination: /templates\n"
                + "      owner: { user: 0 }\n",
                "volumes[1].linkToHostDirectory", "only by directoryOrCreate");
    }

    @Test
    public void mode_on_other_volume_type_is_rejected() {
        assertRejectedBeforeChanges(""
                + "volumes:\n"
                + STATE_VOLUME
                + "  - fileConfig:\n"
                + "      source: a.yml\n"
                + "      mode: \"0600\"\n",
                "volumes[1].fileConfig", "only by directoryOrCreate");
    }

    /** A missing dash merges two types; checking one and running the other must not happen. */
    @Test
    public void several_types_in_one_element_with_owner_are_rejected() {
        assertRejectedBeforeChanges(""
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n"
                + "    dirConfig:\n"
                + "      configPath: seed\n"
                + "      owner: runAs\n"
                + "      mode: \"2770\"\n",
                "volumes[0]", "several volume types", "directoryOrCreate", "dirConfig");
    }

    @Test
    public void invalid_mode_is_rejected() {
        assertRejectedBeforeChanges(""
                + "volumes:\n"
                + STATE_VOLUME
                + "  - directoryOrCreate:\n"
                + "      destination: /shared\n"
                + "      mode: \"2770\"\n",
                "volumes[1].directoryOrCreate.mode", "2770");
    }

    @Test
    public void reference_to_unset_field_is_rejected() {
        assertRejectedBeforeChanges(""
                + "securityContext:\n"
                + "  runAsUser: 1001\n"
                + "volumes:\n"
                + STATE_VOLUME
                + "  - directoryOrCreate:\n"
                + "      destination: /data\n"
                + "      owner: runAs\n",
                "volumes[1].directoryOrCreate.owner.group", "securityContext.runAsGroup");
    }

    @Test
    public void group_without_user_is_rejected() {
        assertRejectedBeforeChanges(""
                + "securityContext:\n"
                + "  runAsGroup: 1001\n"
                + "volumes:\n"
                + STATE_VOLUME,
                "securityContext.runAsGroup", "runAsUser");
    }

    @Test
    public void warns_on_privileged_with_run_as_user() {
        resolve(""
                + "securityContext:\n"
                + "  privileged: true\n"
                + "  runAsUser: 1001\n"
                + "volumes: []\n");

        assertThat(messages).anyMatch(message -> message.contains("privileged"));
    }

    @Test
    public void warns_on_owner_of_readonly_volume() {
        resolve(""
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: /state\n"
                + "      readonly: true\n"
                + "      owner: { user: 1001 }\n");

        assertThat(messages).anyMatch(message -> message.contains("readonly"));
    }

    @Test
    public void legacy_config_has_no_warnings() {
        resolve("volumes:\n" + STATE_VOLUME);

        assertThat(messages).isEmpty();
        assertThat(fileSystem.calls).containsExactly(
                "createDirectories /opt/app",
                "createDirectories /opt/app/state"
        );
    }

    private void assertRejectedBeforeChanges(String aYaml, String... aMessageParts) {
        assertThatThrownBy(() -> resolve(aYaml)).hasMessageContainingAll(aMessageParts);
        assertThat(fileSystem.calls).isEmpty();
    }

    private TDocker resolve(String aYaml) {
        TDocker unresolved = new YamlParser().parseText(HEADER + aYaml, TDocker.class);
        return new DockerResolver().resolve(unresolved, new File("/nonexistent-upload"), fileSystem, logger);
    }
}
