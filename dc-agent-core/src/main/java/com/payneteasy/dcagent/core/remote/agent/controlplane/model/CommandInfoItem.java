package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import com.payneteasy.dcagent.core.config.model.TaskType;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * One configured command (deployment endpoint) on an agent, derived from a config file in
 * CONFIG_DIR. {@code name} is the config file name without extension; {@code type} is best-effort
 * (may be null when it cannot be inferred). The docker command is excluded.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CommandInfoItem {

    String   name;
    TaskType type;
}
