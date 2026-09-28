package com.payneteasy.dcagent.core.config.model.docker.security;

import com.google.gson.annotations.JsonAdapter;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Owner of a host directory mounted as a volume. In dc-docker.yml either the shorthand
 * {@code owner: runAs} (= {@code runAsUser:runAsGroup}) or {@code owner: { user, group }}.
 * A missing field means "do not change".
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
@JsonAdapter(TVolumeOwnerAdapter.class)
public class TVolumeOwner {
    TIdRef user;
    TIdRef group;
}
