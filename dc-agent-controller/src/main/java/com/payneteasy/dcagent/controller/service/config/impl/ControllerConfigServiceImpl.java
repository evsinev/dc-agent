package com.payneteasy.dcagent.controller.service.config.impl;

import com.payneteasy.dcagent.controller.service.config.IControllerConfigService;
import com.payneteasy.dcagent.controller.service.config.model.TAgentHost;
import com.payneteasy.dcagent.controller.service.config.model.TControllerConfig;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

public class ControllerConfigServiceImpl implements IControllerConfigService {

    private final YamlParser yamlParser = new YamlParser();
    private final File       configFile;

    public ControllerConfigServiceImpl(File configFile) {
        this.configFile = SafeFiles.ensureFileExists(configFile);
    }

    @Override
    public TControllerConfig readConfig() {
        return yamlParser.parseFile(configFile, TControllerConfig.class);
    }

    @Override
    public Optional<TAgentHost> findAgentHost(String aName) {
        return readConfig()
                .getAgents()
                .stream()
                .filter(it -> Objects.equals(aName, it.getName()))
                .findAny();
    }
}
