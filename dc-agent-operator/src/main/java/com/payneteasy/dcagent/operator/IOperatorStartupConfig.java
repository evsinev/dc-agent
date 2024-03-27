package com.payneteasy.dcagent.operator;

import com.payneteasy.jetty.util.IJettyStartupParameters;
import com.payneteasy.startup.parameters.AStartupParameter;

import java.io.File;

public interface IOperatorStartupConfig extends IJettyStartupParameters {

    @Override
    @AStartupParameter(name = "JETTY_CONTEXT", value = "/dc-operator")
    String getJettyContext();

    @Override
    @AStartupParameter(name = "JETTY_PORT", value = "8052")
    int getJettyPort();

    @AStartupParameter(name = "REPO_DIR", value = ".")
    File getRepoDir();

    @AStartupParameter(name = "OPERATOR_CONFIG_FILE", value = "./operator-config.yaml")
    File getOperatorConfigFile();

    @AStartupParameter(name = "CONFIG_FILE", value = "operator-config.yaml")
    File getConfigFile();

}
