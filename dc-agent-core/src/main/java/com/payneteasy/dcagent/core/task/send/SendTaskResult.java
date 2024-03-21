package com.payneteasy.dcagent.core.task.send;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class SendTaskResult {
    int    statusCode;
    String text;
}
