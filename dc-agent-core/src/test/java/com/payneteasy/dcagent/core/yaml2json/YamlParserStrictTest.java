package com.payneteasy.dcagent.core.yaml2json;

import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** dc-docker.yml: a key the model does not have is an error, not a silently ignored setting. */
public class YamlParserStrictTest {

    private final YamlParser parser = new YamlParser();

    @Test
    public void rejects_the_typos_from_issue_75_with_path_and_suggestion() {
        String yaml = "name: app\n"
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: /data\n"
                + "      readOnly: true\n"
                + "securityContext:\n"
                + "  readOnlyRootFilesysem: true\n";

        assertThatThrownBy(() -> parser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dc-docker.yml: unknown keys")
                .hasMessageContaining("volumes[0].directoryOrCreate.readOnly: unknown key (did you mean 'readonly'?)")
                .hasMessageContaining("securityContext.readOnlyRootFilesysem: unknown key (did you mean 'readOnlyRootFilesystem'?)");

        // the lenient parser keeps accepting it silently — which is the bug
        assertThat(parser.parseText(yaml, TDocker.class).getSecurityContext().getReadOnlyRootFilesystem()).isNull();
    }

    @Test
    public void rejects_an_unknown_volume_type_and_top_level_key() {
        String yaml = "name: app\n"
                + "imgae:\n"
                + "  name: x\n"
                + "volumes:\n"
                + "  - linkToHostDir:\n"
                + "      source: /a\n";

        assertThatThrownBy(() -> parser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml"))
                .hasMessageContaining("imgae: unknown key (did you mean 'image'?)")
                .hasMessageContaining("volumes[0].linkToHostDir: unknown key (did you mean 'linkToHostDirectory'?)");
    }

    @Test
    public void lists_known_keys_when_nothing_is_close() {
        assertThatThrownBy(() -> parser.parseTextStrict("name: app\ndirectories:\n  whatever: x\n", TDocker.class, "dc-docker.yml"))
                .hasMessageContaining("directories.whatever: unknown key; known keys: sourceBaseDir, destinationBaseDir, containerWorkingDir");
    }

    @Test
    public void rejects_a_duplicate_key_that_would_hide_a_typo() {
        String yaml = "name: app\n"
                + "securityContext:\n"
                + "  readOnlyRootFilesysem: true\n"
                + "securityContext:\n"
                + "  runAsUser: 1001\n";

        assertThatThrownBy(() -> parser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dc-docker.yml")
                .hasMessageContaining("securityContext: duplicate key");
    }

    @Test
    public void checks_list_and_array_elements() {
        String yaml = "name: app\n"
                + "env:\n"
                + "  - name: A\n"
                + "    vaule: 1\n"
                + "args: [ \"--x\", \"--y\" ]\n";

        assertThatThrownBy(() -> parser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml"))
                .hasMessageContaining("env[0].vaule: unknown key (did you mean 'value'?)");
    }

    @Test
    public void accepts_any_key_in_maps() {
        String yaml = "name: app\n"
                + "envMap:\n"
                + "  ANY_NAME: 1\n"
                + "  other.name: 2\n"
                + "boundVariablesMap:\n"
                + "  whatever: x\n";

        TDocker docker = parser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml");

        assertThat(docker.getEnvMap()).containsKeys("ANY_NAME", "other.name");
    }

    @Test
    public void accepts_both_forms_of_a_volume_owner_read_by_its_class_adapter() {
        String yaml = "name: app\n"
                + "securityContext:\n"
                + "  runAsUser: 1001\n"
                + "  runAsGroup: 1001\n"
                + "volumes:\n"
                + "  - directoryOrCreate:\n"
                + "      destination: /state\n"
                + "      owner: runAs\n"
                + "  - directoryOrCreate:\n"
                + "      destination: /shared\n"
                + "      owner: { user: 0, group: runAsGroup }\n"
                + "      mode: \"0770\"\n";

        TDocker docker = parser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml");

        assertThat(docker.getVolumes()).hasSize(2);
    }

    @Test
    public void accepts_every_field_of_the_model() {
        String yaml = "version: 0.0.1\n"
                + "name: app\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "boundVariables:\n"
                + "  - name: A\n"
                + "    value: a\n"
                + "boundVariablesMap:\n"
                + "  B: b\n"
                + "env:\n"
                + "  - name: E\n"
                + "    value: e\n"
                + "    type: VALUE\n"
                + "envMap:\n"
                + "  F: f\n"
                + "args: [ \"--x\" ]\n"
                + "owner: { user: app, group: app }\n"
                + "directories:\n"
                + "  sourceBaseDir: /opt/app\n"
                + "  destinationBaseDir: /app\n"
                + "  containerWorkingDir: /app\n"
                + "securityContext:\n"
                + "  privileged: false\n"
                + "  capabilities:\n"
                + "    add: [ NET_BIND_SERVICE ]\n"
                + "    drop: [ ALL ]\n"
                + "  runAsUser: 1001\n"
                + "  runAsGroup: 1001\n"
                + "  readOnlyRootFilesystem: true\n"
                + "  allowPrivilegeEscalation: false\n"
                + "volumes:\n"
                + "  - directoryOrCreate: { source: s, destination: /d, readonly: true, owner: runAs, mode: \"0700\" }\n"
                + "  - fileConfig: { source: s, destination: /d }\n"
                + "  - dirConfig: { source: s, destination: /d }\n"
                + "  - fileFetchUrl: { url: \"https://example.com/x\", destination: /d }\n"
                + "  - linkToHostDirectory: { source: /s, destination: /d, readonly: true }\n"
                + "  - linkToHostFile: { source: /s, destination: /d, readonly: true }\n"
                + "  - templateFileConfig: { source: s, destination: /d }\n";

        assertThat(StrictKeys.unknownKeys(new Yaml2GsonConverter(true).convertToJson(
                (org.snakeyaml.engine.v2.nodes.MappingNode) new org.snakeyaml.engine.v2.api.lowlevel.Compose(
                        org.snakeyaml.engine.v2.api.LoadSettings.builder().build()).composeString(yaml).orElseThrow()), TDocker.class))
                .isEmpty();
    }
}
