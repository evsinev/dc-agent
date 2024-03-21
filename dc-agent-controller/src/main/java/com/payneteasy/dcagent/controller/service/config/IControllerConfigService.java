package com.payneteasy.dcagent.controller.service.config;

import com.payneteasy.dcagent.controller.service.config.model.TAgentHost;
import com.payneteasy.dcagent.controller.service.config.model.TControllerConfig;

import java.util.Optional;

public interface IControllerConfigService {

    TControllerConfig readConfig();

    Optional<TAgentHost> findAgentHost(String aName);

}
