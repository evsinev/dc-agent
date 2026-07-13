package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** A newly-generated API key sent by the client (write-only — never returned). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class NewApiKey {

    String key;
    String owner;
}
