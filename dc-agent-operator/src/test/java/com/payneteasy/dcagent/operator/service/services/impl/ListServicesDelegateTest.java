package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListResponse;
import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ListServicesDelegateTest {

    private static final TAgentHost AGENT = TAgentHost.builder().name("agent-1").url("http://a").build();

    private static IOperatorConfigService config(InvocationHandler clientHandler) {
        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                ListServicesDelegateTest.class.getClassLoader(),
                new Class[]{IDcAgentControlPlaneRemoteService.class}, clientHandler);
        return (IOperatorConfigService) Proxy.newProxyInstance(
                ListServicesDelegateTest.class.getClassLoader(),
                new Class[]{IOperatorConfigService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "readConfig" -> TOperatorConfig.builder().agents(List.of(AGENT)).build();
                    case "agentClient" -> client;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ServiceInfoItem service(String name) {
        return ServiceInfoItem.builder()
                .name(name)
                .status(ServiceStatus.builder().state(ServiceStateType.UP).build())
                .build();
    }

    @Test
    public void lists_services_from_every_agent() {
        IOperatorConfigService config = config((proxy, method, args) ->
                ServiceListResponse.builder().services(List.of(service("billing"), service("web"))).build());

        HostServiceListResponse response = new ListServicesDelegate(config).listServices(null);

        assertThat(response.getServices()).hasSize(2);
    }

    @Test
    public void results_are_sorted_by_fqsn() {
        IOperatorConfigService config = config((proxy, method, args) ->
                ServiceListResponse.builder().services(List.of(service("web"), service("billing"))).build());

        HostServiceListResponse response = new ListServicesDelegate(config).listServices(null);

        assertThat(response.getServices())
                .extracting(HostServiceItem::getFqsn)
                .isSorted();
    }

    @Test
    public void unreachable_agent_yields_an_error_row() {
        IOperatorConfigService config = config((proxy, method, args) -> {
            throw new IllegalStateException("connection refused");
        });

        HostServiceListResponse response = new ListServicesDelegate(config).listServices(null);

        assertThat(response.getServices())
                .extracting(HostServiceItem::getStatusIndicator)
                .containsExactly(StatusIndicator.ERROR);
    }
}
