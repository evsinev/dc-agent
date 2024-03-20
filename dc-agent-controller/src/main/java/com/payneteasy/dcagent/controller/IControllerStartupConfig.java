package com.payneteasy.dcagent.controller;

import com.payneteasy.jetty.util.IJettyStartupParameters;
import com.payneteasy.startup.parameters.AStartupParameter;

import java.io.File;

public interface IControllerStartupConfig extends IJettyStartupParameters {

    @Override
    @AStartupParameter(name = "JETTY_CONTEXT", value = "/dc-controller")
    String getJettyContext();

    @Override
    @AStartupParameter(name = "JETTY_PORT", value = "8052")
    int getJettyPort();

    @AStartupParameter(name = "JOBS_DIR", value = "./target/jobs")
    File getJobsDir();

    @AStartupParameter(name = "MANAGE_BASE_URL", value = "http://localhost:8052/dc-controller/manage")
    String getControllerManageBaseUrl();
}
