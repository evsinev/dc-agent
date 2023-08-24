package com.payneteasy.dcagent.admin.dao.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TUser {

    String username;
    String password;
    String role;
}
