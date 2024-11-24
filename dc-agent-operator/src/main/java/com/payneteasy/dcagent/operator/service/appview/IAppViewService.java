package com.payneteasy.dcagent.operator.service.appview;

public interface IAppViewService {

    AppViewResult viewApp(AppViewRequest aRequest);

    AppViewResult pushApp(AppPushRequest aRequest);

}
