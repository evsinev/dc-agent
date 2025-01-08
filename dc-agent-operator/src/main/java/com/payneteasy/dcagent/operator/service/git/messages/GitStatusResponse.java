package com.payneteasy.dcagent.operator.service.git.messages;

import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class GitStatusResponse {
    String     currentBranch;
    GitLogItem lastCommit;
}
