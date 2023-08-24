package com.payneteasy.dcagent.admin.service.tokens.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class AccessTokenParameters {
    String accessTokenValue;
    String clientId;
    String clientSecret;
    String deviceId;
}
