package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** One config file for backup: its name (within CONFIG_DIR) and UTF-8 text content. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ConfigFileEntry {

    String name;
    String content;
}
