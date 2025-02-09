package com.payneteasy.dcagent.core.config.model.docker.security;

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
}
