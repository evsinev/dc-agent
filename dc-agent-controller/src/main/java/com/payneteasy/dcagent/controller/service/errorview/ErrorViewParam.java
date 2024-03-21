package com.payneteasy.dcagent.controller.service.errorview;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ErrorViewParam {
    String type;
    String title;
    String description;
}
