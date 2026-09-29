package com.payneteasy.dcagent.core.modules.docker.runtime;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PasswdEntryTemplateTest {

    private static final String IMAGEKIT = "$USERNAME:*:$UID:$GID::/tmp:/sbin/nologin";

    @Test
    public void accepts_the_imagekit_template_and_renders_like_podman() {
        assertThat(PasswdEntryTemplate.validate(IMAGEKIT)).isEmpty();
        assertThat(PasswdEntryTemplate.render(IMAGEKIT, 20500, 20501)).isEqualTo("20500:*:20500:20501::/tmp:/sbin/nologin");
        assertThat(PasswdEntryTemplate.validate("probe-$UID:x:$UID:$GID:probe user:/tmp:/sbin/nologin")).isEmpty();
    }

    @Test
    public void refuses_what_could_break_the_run_script_or_passwd() {
        assertThat(PasswdEntryTemplate.validate("a'b:*:$UID:$GID::/tmp:/sbin/nologin")).isNotEmpty();
        assertThat(PasswdEntryTemplate.validate("a:*:$UID:$GID::/tmp:/sbin/nologin\nroot2:x:0:0::/:/bin/sh")).isNotEmpty();
        assertThat(PasswdEntryTemplate.validate("a:*:$UID:$GID::/tmp:`id`")).isNotEmpty();
        assertThat(PasswdEntryTemplate.validate("a:*:$UID:$GID::/tmp:/bin/\\sh")).isNotEmpty();
        assertThat(PasswdEntryTemplate.validate("")).isNotEmpty();
    }

    @Test
    public void refuses_unknown_placeholders_and_a_wrong_shape() {
        assertThat(PasswdEntryTemplate.validate("$USER:*:$UID:$GID::/tmp:/sbin/nologin")).containsExactly("unknown placeholder '$USER', use $USERNAME, $UID or $GID");
        assertThat(PasswdEntryTemplate.validate("$USERNAME:*:$UID:$GID:/tmp:/sbin/nologin")).containsExactly("needs 7 fields name:password:uid:gid:gecos:home:shell, got 6");
        assertThat(PasswdEntryTemplate.validate("app:*:app:$GID::/tmp:/sbin/nologin")).containsExactly("uid and gid must be numbers or $UID / $GID");
        assertThat(PasswdEntryTemplate.validate("app:*:$UID:$GID::tmp:/sbin/nologin")).containsExactly("the home directory must be an absolute path");
        assertThat(PasswdEntryTemplate.validate(":*:$UID:$GID::/tmp:/sbin/nologin")).containsExactly("the user name is empty");
    }

    @Test
    public void container_runtime_parameter() {
        assertThat(ContainerRuntime.parse("podman")).isEqualTo(ContainerRuntime.PODMAN);
        assertThat(ContainerRuntime.parse(" Docker ")).isEqualTo(ContainerRuntime.DOCKER);
        assertThatThrownBy(() -> ContainerRuntime.parse("containerd")).hasMessageContaining("CONTAINER_RUNTIME must be 'podman' or 'docker'");

        assertThat(ContainerRuntime.PODMAN.matchesVersionOutput("podman version 5.8.0")).isTrue();
        assertThat(ContainerRuntime.PODMAN.matchesVersionOutput("Docker version 29.4.0, build 9d7ad9f")).isFalse();
        assertThat(ContainerRuntime.DOCKER.matchesVersionOutput("Docker version 29.4.0, build 9d7ad9f")).isTrue();
        assertThat(ContainerRuntime.DOCKER.matchesVersionOutput("podman version 5.8.0")).isFalse();
    }

    @Test
    public void tmpfs_entries() {
        assertThat(TmpfsMount.parse("/tmp").argument()).isEqualTo("/tmp");
        assertThat(TmpfsMount.parse("/tmp:rw,size=16m").argument()).isEqualTo("/tmp:rw,size=16m");
        assertThat(TmpfsMount.parse("/var/tmp/").path()).isEqualTo("/var/tmp");
        assertThatThrownBy(() -> TmpfsMount.parse("tmp")).hasMessageContaining("absolute");
        assertThatThrownBy(() -> TmpfsMount.parse("/a/../etc")).hasMessageContaining("'.' and '..'");
        assertThatThrownBy(() -> TmpfsMount.parse("/tmp:rw;rm -rf")).hasMessageContaining("options");
        assertThatThrownBy(() -> TmpfsMount.parse("/tmp dir")).hasMessageContaining("absolute");
    }
}
