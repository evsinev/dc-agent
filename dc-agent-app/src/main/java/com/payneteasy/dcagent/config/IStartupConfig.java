package com.payneteasy.dcagent.config;

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

}
