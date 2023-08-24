package com.payneteasy.dcagent.admin.dao.model;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TAuthConfig {
    List<TRole>   roles;
    List<TUser>   users;
    List<TClient> clients;
}
