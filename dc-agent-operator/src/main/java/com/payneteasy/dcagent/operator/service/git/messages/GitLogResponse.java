package com.payneteasy.dcagent.operator.service.git.messages;

import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class GitLogResponse {
    String           currentBranch;
    List<GitLogItem> commits;
}
