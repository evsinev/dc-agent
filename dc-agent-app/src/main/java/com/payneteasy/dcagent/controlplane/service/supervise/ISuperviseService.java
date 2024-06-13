package com.payneteasy.dcagent.controlplane.service.supervise;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceActionType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;

import java.util.List;

public interface ISuperviseService {

    List<ServiceInfoItem> listServices(VoidRequest aRequest);

    ServiceInfoItem getServiceInfo(String aServiceName);

    void sendAction(String aServiceName, ServiceActionType aAction);
}
