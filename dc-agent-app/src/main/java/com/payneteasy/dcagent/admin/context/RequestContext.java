package com.payneteasy.dcagent.admin.context;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class RequestContext {

    String accessToken;
    String clientId;
    String clientSecret;

}
