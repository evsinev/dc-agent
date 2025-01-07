package com.payneteasy.dcagent.operator.service.git.messages;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class GitPullResponse {
    boolean successful;
}
