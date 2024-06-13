package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListResponse;
import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.payneteasy.dcagent.operator.service.services.impl.HostServiceItemMapper.toHostService;
import static com.payneteasy.dcagent.operator.service.services.model.StatusIndicator.ERROR;
import static java.util.Comparator.comparing;

public class ListServicesDelegate {

    private static final Logger             LOG          = LoggerFactory.getLogger(ListServicesDelegate.class);
    private static final ServiceListRequest LIST_REQUEST = ServiceListRequest.builder().build();

    private final IOperatorConfigService configService;

    public ListServicesDelegate(IOperatorConfigService configService) {
        this.configService = configService;
    }

    public HostServiceListResponse listServices(HostServiceListRequest aRequest) {
        List<TAgentHost>      agents       = configService.readConfig().getAgents();
        List<HostServiceItem> hostServices = new ArrayList<>();

        for (TAgentHost agent : agents) {
            IDcAgentControlPlaneRemoteService client = configService.agentClient(agent.getName());
            try {
                ServiceListResponse   response = client.listServices(LIST_REQUEST);
                List<ServiceInfoItem> services = response.getServices();
                for (ServiceInfoItem service : services) {
                    hostServices.add(toHostService(agent, service));
                }
            } catch (Exception e) {
                LOG.error("Cannot fetch services from {}", agent, e);
                hostServices.add(HostServiceItem.builder()
                        .fqsn(agent.getName() + "/error")
                        .statusName(e.getMessage())
                        .statusIndicator(ERROR)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }

        hostServices.sort(comparing(HostServiceItem::getFqsn));

        return HostServiceListResponse.builder()
                .services(hostServices)
                .build();

    }

}
