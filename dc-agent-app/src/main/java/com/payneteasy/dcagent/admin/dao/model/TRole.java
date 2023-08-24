package com.payneteasy.dcagent.admin.dao.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TRole {
    String       roleName;
    List<String> permissions;
}
