package com.payneteasy.dcagent.core.remote.agent.controlplane;

import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;

public interface IDcAgentControlPlaneRemoteService {

    ServiceListResponse listServices(ServiceListRequest aRequest);

}
