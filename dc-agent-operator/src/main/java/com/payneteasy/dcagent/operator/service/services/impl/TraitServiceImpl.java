package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.operator.service.services.ITraitService;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListResponse;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewResponse;

public class TraitServiceImpl implements ITraitService {

    private final ListServicesDelegate listServicesDelegate;
    private final ViewServiceDelegate  viewServiceDelegate;

    public TraitServiceImpl(ListServicesDelegate listServicesDelegate, ViewServiceDelegate viewServiceDelegate) {
        this.listServicesDelegate = listServicesDelegate;
        this.viewServiceDelegate  = viewServiceDelegate;
    }

    @Override
    public HostServiceListResponse listServices(HostServiceListRequest aRequest) {
        return listServicesDelegate.listServices(aRequest);
    }

    @Override
    public HostServiceViewResponse viewService(HostServiceViewRequest aRequest) {
        return viewServiceDelegate.viewService(aRequest);
    }

}
