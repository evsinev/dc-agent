package com.payneteasy.dcagent.operator.service.app;

import com.payneteasy.dcagent.operator.service.app.messages.AppListRequest;
import com.payneteasy.dcagent.operator.service.app.messages.AppListResponse;
import com.payneteasy.dcagent.operator.service.app.model.TApp;

import java.util.List;

public interface IAppService {

    TApp getApp(String aName);

    List<TApp> listApps();

    AppListResponse listApps(AppListRequest aRequest);

}
