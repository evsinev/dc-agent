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
}
