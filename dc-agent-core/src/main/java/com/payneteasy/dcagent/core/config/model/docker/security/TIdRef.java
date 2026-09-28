package com.payneteasy.dcagent.core.config.model.docker.security;

import com.google.gson.annotations.JsonAdapter;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * A UID or GID in a volume owner: either a number ({@code user: 0}) or a reference to a
 * {@code securityContext} field ({@code group: runAsGroup}). Exactly one field is set.
 * After resolving only {@code id} is set.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
@JsonAdapter(TIdRefAdapter.class)
public class TIdRef {
    Integer   id;
    TIdSource ref;
}
