package com.payneteasy.dcagent.admin.service.daemontools;

import com.payneteasy.dcagent.admin.service.daemontools.model.ServiceInfo;

import java.util.List;

public interface IDaemontoolsService {

    List<ServiceInfo> listServices();

    ServiceInfo getServiceInfo(String aServiceName);

}
