package com.payneteasy.dcagent.core.remote.agent.controlplane;

import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;

public interface IDcAgentControlPlaneRemoteService {

    ServiceListResponse listServices(ServiceListRequest aRequest);

    ServiceViewResponse viewService(ServiceViewRequest aRequest);

    ServiceActionResponse sendAction(ServiceActionRequest aRequest);

}
