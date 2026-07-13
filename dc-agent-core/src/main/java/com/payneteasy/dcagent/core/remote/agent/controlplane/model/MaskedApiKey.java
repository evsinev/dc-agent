package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** A stored API key, masked: a stable id (recomputable from the secret) plus its owner label. */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class MaskedApiKey {

    String maskedId;
    String owner;
}
