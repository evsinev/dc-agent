package com.payneteasy.dcagent.config.impl;

import com.google.gson.Gson;
import com.payneteasy.dcagent.config.ConfigNotFoundException;
import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.config.model.TJarConfig;
import com.payneteasy.dcagent.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.yaml2json.YamlParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

@RequiredArgsConstructor
public class ConfigServiceImpl implements IConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigServiceImpl.class);

    private final File configDir;
    private final Gson gson;

    @Override
    public TZipArchiveConfig getZipArchiveConfig(String aName) {
        return loadConfig(TZipArchiveConfig.class, aName);
    }

    @Override
    public TFetchUrlConfig getFetchUrlConfig() {
        return loadConfig(TFetchUrlConfig.class, "fetch-url");
    }

    private <T> T loadConfig(Class<T> aClass, String aName) {
        File configJsonFile = new File(configDir, aName + ".json");
        if(configJsonFile.exists()) {
            return loadJson(aClass, configJsonFile);
        }

        File configYamlFile = new File(configDir, aName + ".yml");
        if(configYamlFile.exists()) {
            return loadYaml(aClass, configYamlFile);
        }

        throw new IllegalStateException("No any files " + configJsonFile.getAbsolutePath() + " or " + configYamlFile.getAbsolutePath() + " found");

    }

    private <T> T loadYaml(Class<T> aClass, File configYamlFile) {
        YamlParser parser = new YamlParser();
        return parser.parseFile(configYamlFile, aClass);
    }

    private <T> T loadJson(Class<T> aClass, File configJsonFile) {
        try {
            try (FileReader in = new FileReader(configJsonFile)) {
                return gson.fromJson(in, aClass);
            }
        } catch (IOException e) {
            throw new ConfigNotFoundException("Cannot read " + configJsonFile.getAbsolutePath());
        }
    }

    @Override
    public TSaveArtifactConfig getSaveArtifactConfig(String aName) {
        return loadConfig(TSaveArtifactConfig.class, aName);
    }

    @Override
    public TJarConfig getJarConfig(String aName) {
        return loadConfig(TJarConfig.class, aName);
    }

    @Override
    public TDockerConfig getServiceConfig(String aName) {
        return loadConfig(TDockerConfig.class, aName);
    }
}
