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

    TZipArchiveVersionConfig getZipArchiveVersionConfig(String aName);

    /**
     * Where the issuer files of {@code GITLAB_JWT} keys lie ({@code <issuer>.json}); null when this
     * service has none — every GitLab token is then refused.
     */
    default java.nio.file.Path getKeysDir() {
        return null;
    }
}
