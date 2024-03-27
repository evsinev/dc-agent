package com.payneteasy.dcagent.operator.service.config.impl;

import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

public class OperatorConfigServiceImpl implements IOperatorConfigService {

    private final YamlParser yamlParser = new YamlParser();
    private final File       configFile;

    public OperatorConfigServiceImpl(File configFile) {
        this.configFile = SafeFiles.ensureFileExists(configFile);
    }

    @Override
    public TOperatorConfig readConfig() {
        return yamlParser.parseFile(configFile, TOperatorConfig.class);
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
