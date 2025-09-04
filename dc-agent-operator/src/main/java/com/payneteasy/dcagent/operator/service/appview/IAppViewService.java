package com.payneteasy.dcagent.operator.service.appview;

import com.payneteasy.dcagent.operator.service.appview.messages.AppPushRequest;
import com.payneteasy.dcagent.operator.service.appview.messages.AppStatusResponse;
import com.payneteasy.dcagent.operator.service.appview.messages.AppViewRequest;
import com.payneteasy.dcagent.operator.service.appview.model.AppViewResult;

public interface IAppViewService {

    AppViewResult viewApp(AppViewRequest aRequest);

    AppViewResult pushApp(AppPushRequest aRequest);

    AppStatusResponse getAppStatus(AppViewRequest aRequest);

}
