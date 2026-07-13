package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

/**
 * Write-only key operations for create/update. {@code keep} lists masked ids of existing keys to
 * retain (as returned by a prior get); {@code add} carries brand-new secrets. On create, keep is
 * empty. Plaintext secrets never round-trip back to the client.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ApiKeyOps {

    List<String>    keep;
    List<NewApiKey> add;
}
