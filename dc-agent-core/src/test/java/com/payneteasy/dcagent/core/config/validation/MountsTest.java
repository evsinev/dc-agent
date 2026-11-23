package com.payneteasy.dcagent.core.config.validation;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MountsTest {

    private static final List<String> HOST = List.of(
            "22 1 253:0 / / rw,relatime shared:1 - xfs /dev/mapper/root rw",
            "23 22 0:21 / /proc rw shared:2 - proc proc rw",
            "40 22 253:0 /etc/service.d/db/app /srv/stage rw,relatime shared:1 - xfs /dev/mapper/root rw",
            "41 22 253:0 /srv/x /srv/x rw,relatime shared:1 - xfs /dev/mapper/root rw",       // ReadWritePaths: itself
            "42 22 253:0 /tmp/systemd-private-1/tmp /tmp rw shared:1 - xfs /dev/mapper/root rw", // PrivateTmp
            "43 22 253:1 / /data rw - xfs /dev/sdb rw",
            "44 22 253:1 /web /srv/web\\040site rw - xfs /dev/sdb rw");

    @Test
    public void a_bind_mount_gives_a_path_its_source_as_another_name() throws Exception {
        Mounts mounts = Mounts.parse(HOST);

        assertThat(mounts.names(Path.of("/srv/stage/start"))).containsExactlyInAnyOrder(Path.of("/srv/stage/start"), Path.of("/etc/service.d/db/app/start"));
        assertThat(mounts.names(Path.of("/etc/service.d/db/app"))).contains(Path.of("/srv/stage"));
        assertThat(mounts.names(Path.of("/srv/web site/a"))).containsExactlyInAnyOrder(Path.of("/srv/web site/a"), Path.of("/data/web/a"));
    }

    @Test
    public void ordinary_paths_and_self_binds_have_one_name() throws Exception {
        Mounts mounts = Mounts.parse(HOST);

        assertThat(mounts.names(Path.of("/srv/x/a"))).containsExactly(Path.of("/srv/x/a"));
        assertThat(mounts.names(Path.of("/opt/site"))).containsExactly(Path.of("/opt/site"));
        assertThat(mounts.names(Path.of("/tmp/a"))).containsExactly(Path.of("/tmp/a"), Path.of("/tmp/systemd-private-1/tmp/a"));
        assertThat(new Mounts(List.of()).names(Path.of("/srv/a"))).containsExactly(Path.of("/srv/a"));
    }
}
