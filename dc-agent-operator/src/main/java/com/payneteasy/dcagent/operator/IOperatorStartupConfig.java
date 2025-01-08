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

    @AStartupParameter(name = "CONFIG_FILE", value = "operator-config.yaml")
    File getConfigFile();

    @AStartupParameter(name = "ASSETS_INDEX_JS_URI", value = "/dc-operator/assets/index.js")
    String assetsIndexJsUri();

    @AStartupParameter(name = "ASSETS_INDEX_JS_RESOURCE", value = "classpath:assets/index.js")
    String assetsIndexJsResource();

    @AStartupParameter(name = "ASSETS_INDEX_CSS_URI", value = "/dc-operator/assets/index.css")
    String assetsIndexCssUri();

    @AStartupParameter(name = "ASSETS_INDEX_CSS_RESOURCE", value = "classpath:assets/index.css")
    String assetsIndexCssResource();

    @AStartupParameter(name = "REPO_TASKS_RELATIVE_DIR", value = "tasks")
    String tasksDir();

    @AStartupParameter(name = "REPO_APPS_RELATIVE_DIR", value = "apps")
    String appsDir();

    @AStartupParameter(name = "GIT_SSH_USER_HOME_DIR", value = "AUTO_DETECTED")
    String gitSshUserHomeDir();

    @AStartupParameter(name = "GIT_SSH_CONFIG_DIR", value = "AUTO_DETECTED")
    String gitSshGitConfigDir();

}
