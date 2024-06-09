package com.payneteasy.dcagent.operator.service.services;

import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListResponse;

public interface ITraitService {

    HostServiceListResponse listServices(HostServiceListRequest aRequest);

}
