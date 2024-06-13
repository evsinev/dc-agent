package com.payneteasy.dcagent.operator.service.services;

import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListResponse;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewResponse;

public interface ITraitService {

    HostServiceListResponse listServices(HostServiceListRequest aRequest);

    HostServiceViewResponse viewService(HostServiceViewRequest aRequest);

}
