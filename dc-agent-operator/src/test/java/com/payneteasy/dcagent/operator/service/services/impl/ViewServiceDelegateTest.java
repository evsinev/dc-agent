package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewResponse;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

public class ViewServiceDelegateTest {

    private static final TAgentHost AGENT = TAgentHost.builder().name("agent-1").url("http://a").build();

    private static ServiceViewResponse agentView() {
        return ServiceViewResponse.builder()
                .serviceName("billing")
                .serviceInfo(ServiceInfoItem.builder()
                        .name("billing")
                        .status(ServiceStatus.builder().state(ServiceStateType.UP).build())
                        .build())
                .lastLogLines("log-lines")
                .runContent("run")
                .logRunContent("log-run")
                .build();
    }

    private static IOperatorConfigService config() {
        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                ViewServiceDelegateTest.class.getClassLoader(),
                new Class[]{IDcAgentControlPlaneRemoteService.class},
                (proxy, method, args) -> {
                    if ("viewService".equals(method.getName())) {
                        return agentView();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (IOperatorConfigService) Proxy.newProxyInstance(
                ViewServiceDelegateTest.class.getClassLoader(),
                new Class[]{IOperatorConfigService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findRequiredAgentHost" -> AGENT;
                    case "agentClient" -> client;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private HostServiceViewResponse view() {
        return new ViewServiceDelegate(config())
                .viewService(HostServiceViewRequest.builder().fqsn("agent-1/billing").build());
    }

    @Test
    public void returns_the_mapped_service() {
        assertThat(view().getService()).isNotNull();
    }

    @Test
    public void passes_through_the_log_and_run_content() {
        HostServiceViewResponse response = view();

        assertThat(response.getLastLogLines()).isEqualTo("log-lines");
        assertThat(response.getRunContent()).isEqualTo("run");
        assertThat(response.getLogRunContent()).isEqualTo("log-run");
    }
}
