package com.payneteasy.dcagent.operator.service.agent;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.agent.messages.AgentListResponse;

public interface IAgentService {

    AgentListResponse listAgents(VoidRequest aRequest);
}
