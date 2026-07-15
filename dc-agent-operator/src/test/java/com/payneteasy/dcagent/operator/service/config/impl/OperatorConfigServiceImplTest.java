package com.payneteasy.dcagent.operator.service.config.impl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class OperatorConfigServiceImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String CONFIG_YAML =
            "agents:\n"
            + "  - name: agent-1\n"
            + "    url: https://host-1\n"
            + "    cpToken: tok-1\n"
            + "  - name: agent-2\n"
            + "    url: https://host-2\n"
            + "    cpToken: tok-2\n";

    private OperatorConfigServiceImpl service() throws Exception {
        File configFile = folder.newFile("operator.yml");
        Files.write(configFile.toPath(), CONFIG_YAML.getBytes(StandardCharsets.UTF_8));
        return new OperatorConfigServiceImpl(configFile, null);
    }

    @Test
    public void read_config_parses_all_agents() throws Exception {
        assertThat(service().readConfig().getAgents()).hasSize(2);
    }

    @Test
    public void find_agent_host_returns_matching_agent() throws Exception {
        assertThat(service().findAgentHost("agent-1"))
                .get()
                .extracting("url")
                .isEqualTo("https://host-1");
    }

    @Test
    public void find_agent_host_returns_empty_for_unknown_name() throws Exception {
        assertThat(service().findAgentHost("nope")).isEmpty();
    }

    @Test
    public void find_required_agent_host_throws_for_unknown_name() throws Exception {
        OperatorConfigServiceImpl service = service();

        assertThatThrownBy(() -> service.findRequiredAgentHost("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
