package com.payneteasy.dcagent.core.remote.agent.controlplane;

import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;

public interface IDcAgentControlPlaneRemoteService {

    ServiceListResponse listServices(ServiceListRequest aRequest);

    ServiceViewResponse viewService(ServiceViewRequest aRequest);

}
