package com.payneteasy.dcagent.operator.service.info.messages;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/** The operator's own build version (from the jar manifest Implementation-Version). */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class InfoResponse {

    String version;
}
