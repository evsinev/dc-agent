package com.payneteasy.dcagent.operator.service.git.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class GitLogItem {
    public static final GitLogItem EMPTY = GitLogItem.builder()
            .dateFormatted ( "-" )
            .shortMessage  ( "-" )
            .fullMessage   ( "-" )
            .author        ( "-" )
            .author        ( "-" )
            .commiter      ( "-" )
            .commiter      ( "-" )
            .ageFormatted  ( "-" )
            .build();

    String dateFormatted;
    String shortMessage;
    String fullMessage;
    String author;
    String commiter;
    String ageFormatted;
}
