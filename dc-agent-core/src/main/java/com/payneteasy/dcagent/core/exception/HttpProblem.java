package com.payneteasy.dcagent.core.exception;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class HttpProblem {

    int                 status;
    String              type;
    String              title;
    String              detail;
    Map<String, String> context;

    String              memo;
    Map<String, String> env;


}
