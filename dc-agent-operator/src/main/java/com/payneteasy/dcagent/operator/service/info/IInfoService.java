package com.payneteasy.dcagent.operator.service.info;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.info.messages.InfoResponse;

public interface IInfoService {

    InfoResponse info(VoidRequest aRequest);
}
