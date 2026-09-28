package com.payneteasy.dcagent.core.config.model.docker.security;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.junit.Test;

import static com.payneteasy.dcagent.core.config.model.docker.security.TIdSource.RUN_AS_GROUP;
import static com.payneteasy.dcagent.core.config.model.docker.security.TIdSource.RUN_AS_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SecurityContextParseTest {

    private final YamlParser parser = new YamlParser();

    @Test
    public void parses_security_context() {
        TSecurityContext context = parse(
                "securityContext:\n"
                + "  runAsUser: 1001\n"
                + "  runAsGroup: 1002\n"
                + "  readOnlyRootFilesystem: true\n"
                + "  allowPrivilegeEscalation: false\n"
        ).getSecurityContext();

        assertThat(context.getRunAsUser()).isEqualTo(1001);
        assertThat(context.getRunAsGroup()).isEqualTo(1002);
        assertThat(context.getReadOnlyRootFilesystem()).isTrue();
        assertThat(context.getAllowPrivilegeEscalation()).isFalse();
    }

    @Test
    public void absent_fields_are_null() {
        TSecurityContext context = parse("securityContext:\n  privileged: true\n").getSecurityContext();

        assertThat(context.getRunAsUser()).isNull();
        assertThat(context.getRunAsGroup()).isNull();
        assertThat(context.getReadOnlyRootFilesystem()).isNull();
        assertThat(context.getAllowPrivilegeEscalation()).isNull();
    }

    @Test
    public void id_bounds() {
        assertThat(parse("securityContext:\n  runAsUser: 0\n").getSecurityContext().getRunAsUser()).isZero();
        assertThat(parse("securityContext:\n  runAsUser: 2147483647\n").getSecurityContext().getRunAsUser()).isEqualTo(Integer.MAX_VALUE);

        assertThat(parse("securityContext:\n  runAsUser: \"00000001001\"\n").getSecurityContext().getRunAsUser()).isEqualTo(1001);
        assertThat(parse("securityContext:\n  runAsUser: \"000\"\n").getSecurityContext().getRunAsUser()).isZero();

        assertFails("securityContext:\n  runAsUser: 2147483648\n", "securityContext.runAsUser", "2147483648");
        assertFails("securityContext:\n  runAsUser: 00000000002147483648\n", "securityContext.runAsUser");
        assertFails("securityContext:\n  runAsUser: -1\n"        , "securityContext.runAsUser", "-1");
        assertFails("securityContext:\n  runAsGroup: 1e3\n"      , "securityContext.runAsGroup", "1e3");
    }

    @Test
    public void rejects_user_name() {
        assertFails("securityContext:\n  runAsUser: render\n", "securityContext.runAsUser", "render");
    }

    @Test
    public void rejects_empty_id() {
        assertFails("securityContext:\n  runAsUser: \"\"\n", "securityContext.runAsUser");
    }

    @Test
    public void rejects_non_boolean_security_flags() {
        assertFails("securityContext:\n  readOnlyRootFilesystem: yes\n"  , "securityContext.readOnlyRootFilesystem", "yes");
        assertFails("securityContext:\n  allowPrivilegeEscalation: no\n" , "securityContext.allowPrivilegeEscalation", "no");
        assertFails("securityContext:\n  readOnlyRootFilesystem: tru\n"  , "securityContext.readOnlyRootFilesystem", "tru");
        assertFails("securityContext:\n  readOnlyRootFilesystem: null\n" , "securityContext.readOnlyRootFilesystem", "null");
    }

    @Test
    public void accepts_yaml_core_booleans() {
        assertThat(parse("securityContext:\n  readOnlyRootFilesystem: True\n").getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
        assertThat(parse("securityContext:\n  readOnlyRootFilesystem: FALSE\n").getSecurityContext().getReadOnlyRootFilesystem()).isFalse();
    }

    @Test
    public void owner_shorthand_run_as() {
        TVolumeOwner owner = firstVolume("  - directoryOrCreate:\n      destination: /state\n      owner: runAs\n").getOwner();

        assertThat(owner.getUser() ).isEqualTo(TIdRef.builder().ref(RUN_AS_USER ).build());
        assertThat(owner.getGroup()).isEqualTo(TIdRef.builder().ref(RUN_AS_GROUP).build());
    }

    @Test
    public void owner_object_with_number_and_reference() {
        TVolumeOwner owner = firstVolume("  - directoryOrCreate:\n      destination: /shared\n      owner: { user: 0, group: runAsGroup }\n").getOwner();

        assertThat(owner.getUser() ).isEqualTo(TIdRef.builder().id(0).build());
        assertThat(owner.getGroup()).isEqualTo(TIdRef.builder().ref(RUN_AS_GROUP).build());
    }

    @Test
    public void owner_group_only() {
        TVolumeOwner owner = firstVolume("  - directoryOrCreate:\n      destination: /shared\n      owner:\n        group: 2000\n").getOwner();

        assertThat(owner.getUser() ).isNull();
        assertThat(owner.getGroup()).isEqualTo(TIdRef.builder().id(2000).build());
    }

    @Test
    public void mode_keeps_octal_text() {
        assertThat(firstVolume("  - directoryOrCreate:\n      destination: /s\n      mode: 0770\n").getMode()).isEqualTo("0770");
        assertThat(firstVolume("  - directoryOrCreate:\n      destination: /s\n      mode: \"0750\"\n").getMode()).isEqualTo("0750");
    }

    @Test
    public void rejects_bad_owner() {
        String prefix = "volumes:\n  - directoryOrCreate:\n      destination: /s\n      owner: ";
        String path   = "volumes[0].directoryOrCreate.owner";

        assertFails(prefix + "root\n"                , path, "root");
        assertFails(prefix + "{ user: render }\n"    , path + ".user", "render");
        assertFails(prefix + "{ group: runAsUserX }\n", path + ".group", "runAsUserX");
        assertFails(prefix + "{ uid: 1001 }\n"       , path, "uid");
        assertFails(prefix + "{}\n"                  , path, "at least one");
        assertFails(prefix + "[ 1001 ]\n"            , path);
    }

    /**
     * Narrow variant: owner/mode are parsed on every volume type (so Gson does not drop them
     * silently); the resolver rejects them everywhere except directoryOrCreate.
     */
    @Test
    public void owner_and_mode_are_kept_on_other_volume_types() {
        IVolume volume = firstVolume("  - linkToHostDirectory:\n      source: /opt/x\n      destination: /x\n      owner: runAs\n      mode: \"0700\"\n");

        assertThat(volume.getOwner()).isNotNull();
        assertThat(volume.getMode()).isEqualTo("0700");
    }

    @Test
    public void owner_round_trips_through_gson() {
        Gson         gson  = new Gson();
        TVolumeOwner owner = TVolumeOwner.builder()
                .user  ( TIdRef.builder().id(0).build() )
                .group ( TIdRef.builder().ref(RUN_AS_GROUP).build() )
                .build();

        String json = gson.toJson(owner);

        assertThat(json).isEqualTo("{\"user\":0,\"group\":\"runAsGroup\"}");
        assertThat(gson.fromJson(json, TVolumeOwner.class)).isEqualTo(owner);
    }

    private TDocker parse(String aYaml) {
        return parser.parseText("name: test\n" + aYaml, TDocker.class);
    }

    private IVolume firstVolume(String aVolumesYaml) {
        return parse("volumes:\n" + aVolumesYaml).getVolumes().get(0).getVolume();
    }

    private void assertFails(String aYaml, String... aMessageParts) {
        assertThatThrownBy(() -> parse(aYaml))
                .hasMessageContainingAll(aMessageParts);
    }
}
