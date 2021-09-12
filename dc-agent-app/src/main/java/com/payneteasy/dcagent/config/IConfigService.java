package com.payneteasy.dcagent.config;

import com.payneteasy.dcagent.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.config.model.TWarConfig;
import com.payneteasy.dcagent.config.model.TZipArchiveConfig;

public interface IConfigService {

    TZipArchiveConfig getZipArchiveConfig(String aName);

    TFetchUrlConfig getFetchUrlConfig();

    TSaveArtifactConfig getSaveArtifactConfig(String aName);

    TWarConfig getWarConfig(String aName);
}
