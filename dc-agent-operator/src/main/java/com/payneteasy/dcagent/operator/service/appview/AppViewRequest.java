package com.payneteasy.dcagent.operator.service.appview;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
@AllArgsConstructor
public class AppViewRequest {
    String appName;
}
