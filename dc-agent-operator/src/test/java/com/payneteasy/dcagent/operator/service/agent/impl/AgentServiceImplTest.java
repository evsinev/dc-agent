package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.appstatus.AgentAppStatusClient;
import com.payneteasy.dcagent.core.remote.agent.appstatus.AgentAppStatusClientFactory;
import com.payneteasy.dcagent.core.remote.agent.appstatus.TAgentAppStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.agent.messages.AgentListResponse;
import com.payneteasy.dcagent.operator.service.agent.model.TAgentInfo;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentServiceImplTest {

    private static final TAgentHost ALPHA = TAgentHost.builder()
            .name("alpha").url("http://alpha/dc-agent").appStatusToken("t").cpToken("c").build();
    private static final TAgentHost BRAVO = TAgentHost.builder()
            .name("bravo").url("http://bravo/dc-agent").appStatusToken("t").cpToken("c").build();

    @Test
    public void aggregates_status_and_services_and_handles_dead_agents() {
        AgentServiceImpl service = new AgentServiceImpl(
                new StubConfigService(asList(BRAVO, ALPHA))   // deliberately unsorted
                , new StubAppStatusFactory()
        );

        AgentListResponse response = service.listAgents(null);
        List<TAgentInfo>  agents   = response.getAgents();

        assertEquals(2, agents.size());
        // sorted by name
        assertEquals("alpha", agents.get(0).getName());
        assertEquals("bravo", agents.get(1).getName());

        TAgentInfo alpha = agents.get(0);
        assertTrue(alpha.isReachable());
        assertNull(alpha.getError());
        assertEquals("dc-agent", alpha.getAppInstanceName());
        assertEquals("1.2.3", alpha.getAppVersion());
        assertEquals(2, alpha.getServicesTotal());
        assertEquals(1, alpha.getServicesUp());
        assertNull(alpha.getServicesError());
        assertEquals(2, alpha.getServices().size());

        TAgentInfo bravo = agents.get(1);
        assertFalse(bravo.isReachable());
        assertTrue(bravo.getError().contains("down"));
        assertTrue(bravo.getServicesError().contains("down"));
        assertEquals(0, bravo.getServicesTotal());
    }

    private static ServiceInfoItem service(String name, ServiceStateType state) {
        return ServiceInfoItem.builder()
                .name(name)
                .status(ServiceStatus.builder().state(state).build())
                .build();
    }

    private static final class StubConfigService implements IOperatorConfigService {
        private final List<TAgentHost> agents;

        private StubConfigService(List<TAgentHost> agents) {
            this.agents = agents;
        }

        @Override
        public TOperatorConfig readConfig() {
            return TOperatorConfig.builder().agents(agents).build();
        }

        @Override
        public Optional<TAgentHost> findAgentHost(String aName) {
            return agents.stream().filter(a -> a.getName().equals(aName)).findAny();
        }

        @Override
        public TAgentHost findRequiredAgentHost(String aName) {
            return findAgentHost(aName).orElseThrow();
        }

        @Override
        public IDcAgentControlPlaneRemoteService agentClient(String aHost) {
            if ("bravo".equals(aHost)) {
                return new StubControlPlane(null);
            }
            return new StubControlPlane(asList(
                    service("s1", ServiceStateType.UP)
                    , service("s2", ServiceStateType.DOWN)
            ));
        }
    }

    private static final class StubControlPlane implements IDcAgentControlPlaneRemoteService {
        private final List<ServiceInfoItem> services;

        private StubControlPlane(List<ServiceInfoItem> services) {
            this.services = services;
        }

        @Override
        public ServiceListResponse listServices(ServiceListRequest aRequest) {
            if (services == null) {
                throw new IllegalStateException("agent is down");
            }
            return ServiceListResponse.builder().services(services).build();
        }

        @Override
        public ServiceViewResponse viewService(ServiceViewRequest aRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServiceActionResponse sendAction(ServiceActionRequest aRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandListResponse listCommands(CommandListRequest aRequest) {
            return CommandListResponse.builder().commands(List.of()).build();
        }

        @Override public ConfigBackupResponse backupConfigs(ConfigBackupRequest aRequest)            { throw new UnsupportedOperationException(); }
        @Override public CommandGetResponse getCommand(CommandGetRequest aRequest)                   { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createJar(CommandJarRequest aRequest)                   { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createWar(CommandWarRequest aRequest)                   { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createNode(CommandNodeRequest aRequest)                 { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createSaveArtifact(CommandSaveArtifactRequest aRequest) { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createZipArchive(CommandZipArchiveRequest aRequest)     { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createZipDirs(CommandZipDirsRequest aRequest)           { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createFetchUrl(CommandFetchUrlRequest aRequest)         { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse createDocker(CommandDockerRequest aRequest)             { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateJar(CommandJarRequest aRequest)                   { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateWar(CommandWarRequest aRequest)                   { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateNode(CommandNodeRequest aRequest)                 { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateSaveArtifact(CommandSaveArtifactRequest aRequest) { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateZipArchive(CommandZipArchiveRequest aRequest)     { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateZipDirs(CommandZipDirsRequest aRequest)           { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateFetchUrl(CommandFetchUrlRequest aRequest)         { throw new UnsupportedOperationException(); }
        @Override public CommandSaveResponse updateDocker(CommandDockerRequest aRequest)             { throw new UnsupportedOperationException(); }
    }

    private static final class StubAppStatusFactory extends AgentAppStatusClientFactory {
        private StubAppStatusFactory() {
            super(null);
        }

        @Override
        public AgentAppStatusClient createClient(String baseUrl, String bearerToken) {
            boolean down = baseUrl.contains("bravo");
            return new AgentAppStatusClient(null, baseUrl, bearerToken, null, null) {
                @Override
                public TAgentAppStatus fetch() {
                    if (down) {
                        throw new IllegalStateException("agent is down");
                    }
                    return TAgentAppStatus.builder()
                            .type("OK")
                            .appInstanceName("dc-agent")
                            .appVersion("1.2.3")
                            .hostname("alpha-host")
                            .port(8051)
                            .uptimeMs(1_000)
                            .responseEpoch(0)
                            .responseId("id")
                            .build();
                }
            };
        }
    }
}
