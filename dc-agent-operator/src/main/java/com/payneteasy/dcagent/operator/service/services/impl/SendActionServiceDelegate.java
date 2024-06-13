package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceActionRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceActionResponse;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceSendActionRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceSendActionResponse;

public class SendActionServiceDelegate {

    private final IOperatorConfigService configService;

    public SendActionServiceDelegate(IOperatorConfigService configService) {
        this.configService = configService;
    }

    public HostServiceSendActionResponse sendAction(HostServiceSendActionRequest aRequest) {
        Fqsn                              fqsn   = Fqsn.parseFqsn(aRequest.getFqsn());
        IDcAgentControlPlaneRemoteService client = configService.agentClient(fqsn.getHost());

        ServiceActionResponse response = client.sendAction(ServiceActionRequest.builder()
                .serviceName(fqsn.getServiceName())
                .serviceAction(aRequest.getServiceAction())
                .build());

        return HostServiceSendActionResponse.builder()
                .build();
    }
}
