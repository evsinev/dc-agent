package com.payneteasy.dcagent.operator.service.services;

import com.payneteasy.dcagent.operator.service.services.messages.*;

public interface ITraitService {

    HostServiceListResponse listServices(HostServiceListRequest aRequest);

    HostServiceViewResponse viewService(HostServiceViewRequest aRequest);

    HostServiceSendActionResponse sendAction(HostServiceSendActionRequest aRequest);

}
