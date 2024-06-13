package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.operator.service.services.ITraitService;
import com.payneteasy.dcagent.operator.service.services.messages.*;

public class TraitServiceImpl implements ITraitService {

    private final ListServicesDelegate      listServicesDelegate;
    private final ViewServiceDelegate       viewServiceDelegate;
    private final SendActionServiceDelegate sendActionServiceDelegate;

    public TraitServiceImpl(ListServicesDelegate listServicesDelegate, ViewServiceDelegate viewServiceDelegate, SendActionServiceDelegate sendActionServiceDelegate) {
        this.listServicesDelegate      = listServicesDelegate;
        this.viewServiceDelegate       = viewServiceDelegate;
        this.sendActionServiceDelegate = sendActionServiceDelegate;
    }

    @Override
    public HostServiceListResponse listServices(HostServiceListRequest aRequest) {
        return listServicesDelegate.listServices(aRequest);
    }

    @Override
    public HostServiceViewResponse viewService(HostServiceViewRequest aRequest) {
        return viewServiceDelegate.viewService(aRequest);
    }

    @Override
    public HostServiceSendActionResponse sendAction(HostServiceSendActionRequest aRequest) {
        return sendActionServiceDelegate.sendAction(aRequest);
    }
}
