package com.payneteasy.dcagent.core.config.service;

import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;

public interface IConfigService {

    TZipArchiveConfig getZipArchiveConfig(String aName);

    TFetchUrlConfig getFetchUrlConfig();

    TSaveArtifactConfig getSaveArtifactConfig(String aName);

    TJarConfig getJarConfig(String aName);

    TDockerConfig getServiceConfig(String aName);
}
