package com.payneteasy.dcagent.operator.service.backup;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ConfigBackupResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ConfigFileEntry;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentConfigBackupServiceBackupAllTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final TAgentHost AGENT = TAgentHost.builder().name("sandbox-1").build();

    private static IOperatorConfigService config(List<TAgentHost> agents, InvocationHandler clientHandler) {
        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                AgentConfigBackupServiceBackupAllTest.class.getClassLoader(),
                new Class[]{IDcAgentControlPlaneRemoteService.class}, clientHandler);
        return (IOperatorConfigService) Proxy.newProxyInstance(
                AgentConfigBackupServiceBackupAllTest.class.getClassLoader(),
                new Class[]{IOperatorConfigService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "readConfig" -> TOperatorConfig.builder().agents(agents).build();
                    case "agentClient" -> client;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private long zipCount(String agent) {
        File[] zips = new File(folder.getRoot(), agent).listFiles((d, n) -> n.endsWith(".zip"));
        return zips == null ? 0 : zips.length;
    }

    @Test
    public void fetches_and_archives_each_agents_configs() {
        IOperatorConfigService config = config(List.of(AGENT), (proxy, method, args) ->
                ConfigBackupResponse.builder()
                        .files(List.of(ConfigFileEntry.builder().name("billing.json").content("{}").build()))
                        .build());

        new AgentConfigBackupService(config, folder.getRoot(), 5).backupAll();

        assertThat(zipCount("sandbox-1")).isEqualTo(1);
    }

    @Test
    public void does_nothing_when_there_are_no_agents() {
        IOperatorConfigService config = config(null, (proxy, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        });

        new AgentConfigBackupService(config, folder.getRoot(), 5).backupAll();

        assertThat(folder.getRoot().listFiles()).isEmpty();
    }

    @Test
    public void unreachable_agent_is_skipped_without_failing() {
        IOperatorConfigService config = config(List.of(AGENT), (proxy, method, args) -> {
            throw new IllegalStateException("connection refused");
        });

        new AgentConfigBackupService(config, folder.getRoot(), 5).backupAll();

        assertThat(zipCount("sandbox-1")).isZero();
    }
}
