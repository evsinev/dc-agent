package com.payneteasy.dcagent;

import com.payneteasy.jetty.util.IJettyStartupParameters;
import com.payneteasy.startup.parameters.AStartupParameter;

import java.io.File;

public interface IStartupConfig extends IJettyStartupParameters {

    @Override
    @AStartupParameter(name = "WEB_SERVER_PORT", value = "8051")
    int getJettyPort();

    @Override
    @AStartupParameter(name = "WEB_SERVER_CONTEXT", value = "/dc-agent")
    String getJettyContext();

    @AStartupParameter(name = "CONFIG_DIR", value = "./config")
    File getConfigDir();

    @AStartupParameter(name = "OPT_DIR", value = "./opt")
    File getOptDir();

    @AStartupParameter(name = "TEMP_DIR", value = "/tmp")
    File getTempDir();

    @AStartupParameter(name = "DOCKER_DELETE_TEMP_DIR", value = "true")
    boolean isDockerDeleteTempDir();

    @AStartupParameter(name = "SERVICES_DEFINITION_DIR", value = "/etc/service.d")
    File getServicesDefinitionDir();

    @AStartupParameter(name = "SERVICES_DIR", value = "/service")
    File getServicesDir();

    @AStartupParameter(name = "SERVICES_LOG_DIR", value = "/var/log")
    File getServicesLogDir();

    @AStartupParameter(name = "CONTROL_PLANE_TOKEN", value = "REPLACE_THIS_TEST_CONTROL_PLANE_TOKEN")
    String controlPlaneToken();

    @AStartupParameter(name = "CONTROL_PLANE_ENABLED", value = "false")
    boolean isControlPlaneEnabled();

    @AStartupParameter(name = "DAEMONTOOLS_SVC_PATH", value = "/usr/bin/svc")
    String getSvcCommand();

    @AStartupParameter(name = "DAEMONTOOLS_SVSTAT_PATH", value = "/usr/bin/svstat")
    String getSvstatCommand();

    @AStartupParameter(name = "APP_INSTANCE_NAME", value = "dc-agent")
    String appInstanceName();

    @AStartupParameter(name = "APP_STATUS_TOKEN", value = "yWbtRDwuMWe8UScKUIrdD0HCsQMcQnBIvPi0HbhaaWWAvLQqRYWa7VoRvoKjv9bW", maskVariable = true)
    String appStatusToken();

}
