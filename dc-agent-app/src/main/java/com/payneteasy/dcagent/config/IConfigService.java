package com.payneteasy.dcagent.config;

import com.payneteasy.dcagent.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.config.model.TZipArchiveConfig;

public interface IConfigService {

    TZipArchiveConfig getZipArchiveConfig(String aName);

    TFetchUrlConfig getFetchUrlConfig();
}
