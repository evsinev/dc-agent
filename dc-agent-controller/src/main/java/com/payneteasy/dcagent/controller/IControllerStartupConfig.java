package com.payneteasy.dcagent.controller;

import com.payneteasy.jetty.util.IJettyStartupParameters;
import com.payneteasy.startup.parameters.AStartupParameter;

public interface IControllerStartupConfig extends IJettyStartupParameters {

    @Override
    @AStartupParameter(name = "JETTY_CONTEXT", value = "/dc-agent-controller")
    String getJettyContext();

    @Override
    @AStartupParameter(name = "JETTY_PORT", value = "8052")
    int getJettyPort();

}
