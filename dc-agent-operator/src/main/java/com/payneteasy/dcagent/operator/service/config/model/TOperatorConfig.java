package com.payneteasy.dcagent.operator.service.config.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TOperatorConfig {

    List<TAgentHost> agents;

}
