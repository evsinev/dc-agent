package com.payneteasy.dcagent.admin.service.tokens.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class CreateTokenParameters {
    @Nonnull String username;
    @Nonnull String password;
    @Nonnull String clientId;
    @Nonnull String clientSecret;
}
