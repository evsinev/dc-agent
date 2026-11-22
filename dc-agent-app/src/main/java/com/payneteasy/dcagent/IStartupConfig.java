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

    /**
     * Take the listening socket from fd 0 ({@code System.inheritedChannel()}) instead of opening
     * {@code WEB_SERVER_PORT}: a launcher such as {@code dc-agent-landlock --listen-tcp} opens it and
     * then forbids the agent to bind or listen on anything. The start is refused when fd 0 is not a
     * listening TCP socket — never a silent fallback to a port of its own.
     */
    @AStartupParameter(name = "WEB_SERVER_INHERITED", value = "false")
    boolean isJettyInherited();

    @Override
    @AStartupParameter(name = "WEB_SERVER_CONTEXT", value = "/dc-agent")
    String getJettyContext();

    /**
     * How long a connection may stay silent ({@code 30s}, {@code 10m}). Jetty's own default is 30 s; a
     * {@code zip-archive-version} call is silent while it waits for the directory lock (up to 1 min)
     * and for the service's reload (up to its {@code waitTimeout}), and its config is refused unless
     * both fit in this value. A reverse proxy in front needs a read timeout of the same size.
     */
    @AStartupParameter(name = "WEB_SERVER_IDLE_TIMEOUT", value = "10m")
    String getJettyIdleTimeout();

    /**
     * Where commands may write (their {@code dir}, the directory of a jar/war): absolute paths
     * separated by commas and/or whitespace. A path outside them is refused at start and on a call.
     */
    @AStartupParameter(name = "COMMAND_DIRS", value = "/opt,/srv")
    String getCommandDirs();

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
    @AStartupParameter(name = "CONTROL_PLANE_TOKEN", value = DEFAULT_CONTROL_PLANE_TOKEN, maskVariable = true)
    String controlPlaneToken();

    @AStartupParameter(name = "CONTROL_PLANE_ENABLED", value = "false")
    boolean isControlPlaneEnabled();

    @AStartupParameter(name = "APP_INSTANCE_NAME", value = "dc-agent")
    String appInstanceName();

    @AStartupParameter(name = "APP_STATUS_TOKEN", value = "yWbtRDwuMWe8UScKUIrdD0HCsQMcQnBIvPi0HbhaaWWAvLQqRYWa7VoRvoKjv9bW", maskVariable = true)
    String appStatusToken();

}
