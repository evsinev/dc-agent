package com.payneteasy.dcagent;

import com.payneteasy.jetty.util.IJettyStartupParameters;
import com.payneteasy.startup.parameters.AStartupParameter;

import java.io.File;

public interface IStartupConfig extends IJettyStartupParameters {

    /** Published in this repository: the agent refuses to start with it when the control plane is on. */
    String DEFAULT_CONTROL_PLANE_TOKEN = "REPLACE_THIS_TEST_CONTROL_PLANE_TOKEN";

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

    /**
     * What {@code docker} in the run scripts really is: {@code podman} (podman-docker wrapper) or
     * {@code docker}. Only matters for securityContext.passwdEntry.
     */
    @AStartupParameter(name = "CONTAINER_RUNTIME", value = "podman")
    String getContainerRuntime();

    @AStartupParameter(name = "SERVICES_DEFINITION_DIR", value = "/etc/service.d")
    File getServicesDefinitionDir();

    @AStartupParameter(name = "SERVICES_DIR", value = "/service")
    File getServicesDir();

    @AStartupParameter(name = "SERVICES_LOG_DIR", value = "/var/log")
    File getServicesLogDir();

    /** Required (not the default, not empty) when {@code CONTROL_PLANE_ENABLED=true}. */
    @AStartupParameter(name = "CONTROL_PLANE_TOKEN", value = DEFAULT_CONTROL_PLANE_TOKEN)
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
