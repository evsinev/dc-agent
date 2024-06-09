package com.payneteasy.dcagent.controlplane;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controlplane.service.daemontools.IDaemontoolsService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;

import java.util.List;

public class DcAgentControlPlaneRemoteServiceImpl implements IDcAgentControlPlaneRemoteService {

    private final IDaemontoolsService daemontoolsService;

    public DcAgentControlPlaneRemoteServiceImpl(IDaemontoolsService daemontoolsService) {
        this.daemontoolsService = daemontoolsService;
    }

    @Override
    public ServiceListResponse listServices(ServiceListRequest aRequest) {
        List<ServiceInfoItem> services = daemontoolsService.listServices(VoidRequest.VOID_REQUEST);

        return ServiceListResponse.builder()
                .services(services)
                .build();
    }
}
