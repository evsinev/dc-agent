package com.payneteasy.dcagent.controlplane;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controlplane.service.serviceview.ServiceViewDelegate;
import com.payneteasy.dcagent.controlplane.service.supervise.ISuperviseService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;

import java.util.List;

public class DcAgentControlPlaneRemoteServiceImpl implements IDcAgentControlPlaneRemoteService {

    private final ISuperviseService   superviseService;
    private final ServiceViewDelegate serviceViewDelegate;

    public DcAgentControlPlaneRemoteServiceImpl(ISuperviseService daemontoolsService, ServiceViewDelegate serviceViewDelegate) {
        this.superviseService    = daemontoolsService;
        this.serviceViewDelegate = serviceViewDelegate;
    }

    @Override
    public ServiceListResponse listServices(ServiceListRequest aRequest) {
        List<ServiceInfoItem> services = superviseService.listServices(VoidRequest.VOID_REQUEST);

        return ServiceListResponse.builder()
                .services(services)
                .build();
    }

    @Override
    public ServiceViewResponse viewService(ServiceViewRequest aRequest) {
        return serviceViewDelegate.getServiceView(aRequest.getServiceName());
    }

    @Override
    public ServiceActionResponse sendAction(ServiceActionRequest aRequest) {
        superviseService.sendAction(aRequest.getServiceName(), aRequest.getServiceAction());
        return ServiceActionResponse.builder().build();
    }
}
