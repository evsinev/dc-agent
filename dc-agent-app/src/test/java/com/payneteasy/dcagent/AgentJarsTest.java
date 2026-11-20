package com.payneteasy.dcagent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentJarsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void the_started_path_is_kept_whatever_its_name() throws Exception {
        Path release = Files.writeString(tmp.getRoot().toPath().resolve("agent.jar"), "jar");
        Path current = Files.createSymbolicLink(tmp.getRoot().toPath().resolve("current"), release);

        assertThat(DcAgentApplication.agentJars(current.toString(), release)).containsExactly(current, release);
    }

    @Test
    public void a_class_path_of_several_entries_or_a_directory_is_not_a_jar() {
        String several = tmp.getRoot() + File.pathSeparator + tmp.getRoot();

        assertThat(DcAgentApplication.agentJars(several, null)).isEmpty();
        assertThat(DcAgentApplication.agentJars(tmp.getRoot().toString(), tmp.getRoot().toPath())).isEmpty();
        assertThat(DcAgentApplication.agentJars("", null)).isEmpty();
    }

    /** Outside /opt/dc-agent on purpose: the agent's home is protected anyway, a test there proves nothing. */
    @Test
    public void a_native_agent_started_through_path_is_protected_at_the_link_it_was_found_by() throws Exception {
        Path root     = tmp.getRoot().toPath().toRealPath();
        Path release  = Files.createDirectories(root.resolve("srv/releases")).resolve("dc-agent");
        Files.writeString(release, "binary");
        release.toFile().setExecutable(true);
        Path launcher = Files.createDirectories(root.resolve("srv/launcher"));
        Path link     = Files.createSymbolicLink(launcher.resolve("dc-agent"), release);
        Path empty    = Files.createDirectories(root.resolve("bin"));

        // dc-agent found through PATH: the first executable on it, the empty bin skipped
        assertThat(DcAgentApplication.nativeExecutables("dc-agent", empty + ":" + launcher, root, release))
                .containsExactly(release, link);

        // a relative argv[0] with a slash: from the working directory, PATH ignored
        assertThat(DcAgentApplication.nativeExecutables("srv/launcher/dc-agent", "/nonexistent", root, release))
                .containsExactly(release, root.resolve("srv/launcher/dc-agent"));

        // an absolute one as is; nothing on PATH: only /proc/self/exe
        assertThat(DcAgentApplication.nativeExecutables(link.toString(), null, root, release)).containsExactly(release, link);
        assertThat(DcAgentApplication.nativeExecutables("dc-agent", empty.toString(), root, release)).containsExactly(release);
    }

    @Test
    public void a_launcher_link_found_through_path_is_protected_by_the_policy() throws Exception {
        Path root     = tmp.getRoot().toPath().toRealPath();
        Path release  = Files.createDirectories(root.resolve("srv/releases")).resolve("dc-agent");
        Files.writeString(release, "binary");
        release.toFile().setExecutable(true);
        Path launcher = Files.createDirectories(root.resolve("srv/launcher"));
        Files.createSymbolicLink(launcher.resolve("dc-agent"), release);
        Files.createDirectories(root.resolve("srv/launcher2"));

        java.util.List<Path> executables = DcAgentApplication.nativeExecutables("dc-agent", launcher.toString(), root, release);
        com.payneteasy.dcagent.core.config.validation.CommandPathPolicy policy =
                new com.payneteasy.dcagent.core.config.validation.CommandPathPolicy(java.util.List.of(root.resolve("srv")),
                        executables, java.util.List.of());

        assertThat(policy.checkDirectory("dir", launcher.toString())).singleElement().asString().contains("overlaps");
        // twin: the directory next to it is free
        assertThat(policy.checkDirectory("dir", root.resolve("srv/launcher2").toString())).isEmpty();
    }

    @Test
    public void the_first_readable_host_trust_store_is_chosen() throws Exception {
        Path el     = tmp.getRoot().toPath().resolve("pki-cacerts");
        Path debian = Files.writeString(tmp.getRoot().toPath().resolve("ssl-cacerts"), "store");

        assertThat(DcAgentApplication.chooseTrustStore(java.util.List.of(el, debian))).isEqualTo(debian);
        Files.writeString(el, "store");
        assertThat(DcAgentApplication.chooseTrustStore(java.util.List.of(el, debian))).isEqualTo(el);
        assertThat(DcAgentApplication.chooseTrustStore(java.util.List.of(tmp.getRoot().toPath().resolve("none")))).isNull();
    }
}
