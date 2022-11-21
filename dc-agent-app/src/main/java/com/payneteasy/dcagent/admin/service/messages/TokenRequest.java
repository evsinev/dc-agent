package com.payneteasy.dcagent.admin.service.messages;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TokenRequest {

    String username;
    String password;
    String clientId;
    String clientSecret;
    
}
