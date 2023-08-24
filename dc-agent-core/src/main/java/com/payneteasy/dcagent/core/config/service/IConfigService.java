package com.payneteasy.dcagent.core.config.service;

import com.payneteasy.dcagent.core.config.model.*;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;

public interface IConfigService {

    TZipArchiveConfig getZipArchiveConfig(String aName);

    TFetchUrlConfig getFetchUrlConfig();

    TSaveArtifactConfig getSaveArtifactConfig(String aName);

    TJarConfig getJarConfig(String aName);

    TDockerConfig getServiceConfig(String aName);

    TZipDirsConfig getZipDirsConfig(String aName);
}
