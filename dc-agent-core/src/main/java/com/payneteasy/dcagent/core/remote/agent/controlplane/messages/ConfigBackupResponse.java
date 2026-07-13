package com.payneteasy.dcagent.core.remote.agent.controlplane.messages;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ConfigFileEntry;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

/** All of an agent's config files (raw, including secrets) for the operator's periodic backup. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ConfigBackupResponse {

    List<ConfigFileEntry> files;
}
