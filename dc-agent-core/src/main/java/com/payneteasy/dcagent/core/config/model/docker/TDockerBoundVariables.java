package com.payneteasy.dcagent.core.config.model.docker;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

/**
 * The part of dc-docker.yml read before Handlebars substitution. Only the variables: typed
 * fields like {@code runAsUser: "{{ UID }}"} are valid only after substitution.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TDockerBoundVariables {
    List<BoundVariable> boundVariables;
    Map<String, String> boundVariablesMap;
}
