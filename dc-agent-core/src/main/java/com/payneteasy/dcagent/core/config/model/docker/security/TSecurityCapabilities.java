package com.payneteasy.dcagent.core.config.model.docker.security;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TSecurityCapabilities {
    List<String> add;
    List<String> drop;
}
