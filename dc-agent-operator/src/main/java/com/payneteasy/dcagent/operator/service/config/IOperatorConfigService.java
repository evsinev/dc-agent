package com.payneteasy.dcagent.operator.service.config;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.config.model.TOperatorConfig;

import java.util.Optional;

public interface IOperatorConfigService {

    TOperatorConfig readConfig();

    Optional<TAgentHost> findAgentHost(String aAgentName);

    TAgentHost findRequiredAgentHost(String aAgentName);

    IDcAgentControlPlaneRemoteService agentClient(String aHost);

}
