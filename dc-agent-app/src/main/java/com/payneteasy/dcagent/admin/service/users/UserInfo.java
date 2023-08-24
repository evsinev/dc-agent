package com.payneteasy.dcagent.admin.service.users;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class UserInfo {
    String       username;
    List<String> actions;
}
