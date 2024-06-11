package com.payneteasy.dcagent.controlplane;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controlplane.service.daemontools.IDaemontoolsService;
import com.payneteasy.dcagent.controlplane.service.serviceview.ServiceViewDelegate;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;

import java.util.List;

public class DcAgentControlPlaneRemoteServiceImpl implements IDcAgentControlPlaneRemoteService {

    private final IDaemontoolsService daemontoolsService;
    private final ServiceViewDelegate serviceViewDelegate;

    public DcAgentControlPlaneRemoteServiceImpl(IDaemontoolsService daemontoolsService, ServiceViewDelegate serviceViewDelegate) {
        this.daemontoolsService  = daemontoolsService;
        this.serviceViewDelegate = serviceViewDelegate;
    }

    @Override
    public ServiceListResponse listServices(ServiceListRequest aRequest) {
        List<ServiceInfoItem> services = daemontoolsService.listServices(VoidRequest.VOID_REQUEST);

        return ServiceListResponse.builder()
                .services(services)
                .build();
    }

    @Override
    public ServiceViewResponse viewService(ServiceViewRequest aRequest) {
        return serviceViewDelegate.getServiceView(aRequest.getServiceName());
    }
}
