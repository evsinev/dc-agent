package com.payneteasy.dcagent.core.config.model.docker.security;

import com.google.gson.annotations.JsonAdapter;
import com.payneteasy.dcagent.core.util.gson.StrictBooleanAdapter;
import com.payneteasy.dcagent.core.util.gson.StrictIdAdapter;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TSecurityContext {
    Boolean               privileged;
    TSecurityCapabilities capabilities;

    /** {@code --user runAsUser[:runAsGroup]} */
    @JsonAdapter(StrictIdAdapter.class)      Integer runAsUser;
    @JsonAdapter(StrictIdAdapter.class)      Integer runAsGroup;

    /** {@code --read-only} */
    @JsonAdapter(StrictBooleanAdapter.class) Boolean readOnlyRootFilesystem;

    /** {@code false} → {@code --security-opt no-new-privileges} */
    @JsonAdapter(StrictBooleanAdapter.class) Boolean allowPrivilegeEscalation;
}
