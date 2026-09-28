package com.payneteasy.dcagent.core.modules.docker.resolver;

import org.junit.Test;

import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

public class VolumeModeTest {

    @Test
    public void valid_modes() {
        assertThat(VolumeMode.isValid("770")).isTrue();
        assertThat(VolumeMode.isValid("0770")).isTrue();
        assertThat(VolumeMode.isValid("0000")).isTrue();
    }

    @Test
    public void invalid_modes() {
        assertThat(VolumeMode.isValid(null)).isFalse();
        assertThat(VolumeMode.isValid("")).isFalse();
        assertThat(VolumeMode.isValid("78")).isFalse();
        assertThat(VolumeMode.isValid("0780")).isFalse();
        assertThat(VolumeMode.isValid("2770")).isFalse();   // setgid not supported
        assertThat(VolumeMode.isValid("rwxr-x---")).isFalse();
        assertThat(VolumeMode.isValid("00770")).isFalse();
    }

    @Test
    public void to_permissions() {
        assertThat(VolumeMode.toPermissions("0750")).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(VolumeMode.toPermissions("640")).isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
        assertThat(VolumeMode.toPermissions("0007")).isEqualTo(PosixFilePermissions.fromString("------rwx"));
    }
}
