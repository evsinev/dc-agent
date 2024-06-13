package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewResponse;

import static com.payneteasy.dcagent.operator.service.services.impl.HostServiceItemMapper.toHostService;

public class ViewServiceDelegate {

    private final IOperatorConfigService configService;

    public ViewServiceDelegate(IOperatorConfigService configService) {
        this.configService = configService;
    }

    public HostServiceViewResponse viewService(HostServiceViewRequest aRequest) {
        Fqsn                              fqsn   = Fqsn.parseFqsn(aRequest.getFqsn());
        TAgentHost                        agent = configService.findRequiredAgentHost(fqsn.getHost());
        IDcAgentControlPlaneRemoteService client = configService.agentClient(fqsn.getHost());

        ServiceViewResponse serviceView = client.viewService(ServiceViewRequest.builder()
                .serviceName(fqsn.getServiceName())
                .build());

        return HostServiceViewResponse.builder()
                .service        ( toHostService(agent, serviceView.getServiceInfo()))
                .lastLogLines   ( serviceView.getLastLogLines()  )
                .runContent     ( serviceView.getRunContent()    )
                .logRunContent  ( serviceView.getLogRunContent() )
                .build();
    }
}
