package com.payneteasy.dcagent.controlplane.service.daemontools;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;

import java.util.List;

public interface IDaemontoolsService {

    List<ServiceInfoItem> listServices(VoidRequest aRequest);

    ServiceInfoItem getServiceInfo(String aServiceName);

}
