package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceActionResponse;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceSendActionRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceSendActionResponse;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class SendActionServiceDelegateTest {

    @Test
    public void forwards_the_action_to_the_agent_and_returns_a_response() {
        AtomicBoolean called = new AtomicBoolean(false);

        IDcAgentControlPlaneRemoteService client = (IDcAgentControlPlaneRemoteService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{IDcAgentControlPlaneRemoteService.class},
                (proxy, method, args) -> {
                    if ("sendAction".equals(method.getName())) {
                        called.set(true);
                        return ServiceActionResponse.builder().build();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IOperatorConfigService config = (IOperatorConfigService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{IOperatorConfigService.class},
                (proxy, method, args) -> {
                    if ("agentClient".equals(method.getName())) {
                        return client;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        HostServiceSendActionResponse response = new SendActionServiceDelegate(config)
                .sendAction(HostServiceSendActionRequest.builder().fqsn("agent-1/billing").build());

        assertThat(response).isNotNull();
        assertThat(called).isTrue();
    }
}
