package com.payneteasy.dcagent.operator.service.config.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.client.DcAgentControlPlaneClientFactory;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

public class OperatorConfigServiceImpl implements IOperatorConfigService {

    private static final Logger LOG = LoggerFactory.getLogger( OperatorConfigServiceImpl.class );

    private final YamlParser                       yamlParser = new YamlParser();
    private final File                             configFile;
    private final DcAgentControlPlaneClientFactory clientFactory;

    public OperatorConfigServiceImpl(File configFile, DcAgentControlPlaneClientFactory clientFactory) {
        this.configFile    = configFile;
        this.clientFactory = clientFactory;
    }

    @Override
    public TOperatorConfig readConfig() {
        LOG.debug("Reading file {}", configFile.getAbsolutePath());
        return yamlParser.parseFile(configFile, TOperatorConfig.class);
    }

    @Override
    public TAgentHost findRequiredAgentHost(String aAgentName) {
        return findAgentHost(aAgentName).orElseThrow(() -> new IllegalArgumentException("No agent host " + aAgentName));
    }

    public Optional<TAgentHost> findAgentHost(String aName) {
        return readConfig()
                .getAgents()
                .stream()
                .filter(it -> Objects.equals(aName, it.getName()))
                .findAny();
    }

    @Override
    public IDcAgentControlPlaneRemoteService agentClient(String aHost) {
        TAgentHost agent   = findAgentHost(aHost).orElseThrow(() -> new IllegalArgumentException("no host " + aHost));
        String     token   = agent.getCpToken();
        String     baseUrl = agent.getUrl();
        return clientFactory.createClient(baseUrl, token);
    }
}
