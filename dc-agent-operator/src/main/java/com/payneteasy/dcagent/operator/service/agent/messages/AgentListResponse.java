package com.payneteasy.dcagent.operator.service.agent.messages;

import com.payneteasy.dcagent.operator.service.agent.model.TAgentInfo;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class AgentListResponse {

    List<TAgentInfo> agents;
}
