package com.payneteasy.dcagent;

import com.payneteasy.startup.parameters.AStartupParameter;

import java.io.File;

public interface IStartupConfig {

    @AStartupParameter(name = "WEB_SERVER_PORT", value = "8051")
    int webServerPort();

    @AStartupParameter(name = "WEB_SERVER_CONTEXT", value = "/dc-agent")
    String webServerContext();

    @AStartupParameter(name = "CONFIG_DIR", value = "./config")
    File getConfigDir();

    @AStartupParameter(name = "OPT_DIR", value = "./opt")
    File getOptDir();

    @AStartupParameter(name = "TEMP_DIR", value = "/tmp")
    File getTempDir();

    @AStartupParameter(name = "SERVICES_DEFINITION_DIR", value = "/etc/service.d")
    File getServicesDefinitionDir();

    @AStartupParameter(name = "SERVICES_DIR", value = "/service")
    File getServicesDir();

    @AStartupParameter(name = "SERVICES_LOG_DIR", value = "/var/log")
    File getServicesLogDir();

    @AStartupParameter(name = "UI_ADMIN_ENABLED", value = "false")
    boolean isUiAdminEnabled();

    @AStartupParameter(name = "CONTROL_PLANE_TOKEN", value = "REPLACE_THIS_TEST_CONTROL_PLANE_TOKEN")
    String controlPlaneToken();

    @AStartupParameter(name = "CONTROL_PLANE_ENABLED", value = "false")
    boolean isControlPlaneEnabled();

    @AStartupParameter(name = "DAEMONTOOLS_SVC_PATH", value = "/usr/bin/svc")
    String getSvcCommand();

    @AStartupParameter(name = "DAEMONTOOLS_SVSTAT_PATH", value = "/usr/bin/svstat")
    String getSvstatCommand();
}
