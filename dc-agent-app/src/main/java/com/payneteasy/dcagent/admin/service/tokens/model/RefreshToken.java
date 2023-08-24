package com.payneteasy.dcagent.admin.service.tokens.model;

import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class RefreshToken {

    @Nonnull
    String refreshTokenValue;

}
