package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;

import static com.payneteasy.dcagent.operator.service.services.impl.HostServiceItemMapper.formatAge;
import static java.lang.System.currentTimeMillis;

public class GitLogItemMapper {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss Z");

    public static GitLogItem of(RevCommit aCommit) {
        ZonedDateTime date = ZonedDateTime.ofInstant(
                  Instant.ofEpochSecond(aCommit.getCommitTime())
                , ZoneOffset.UTC
        );

        return GitLogItem.builder()
                .dateFormatted ( date.format(FORMATTER))
                .shortMessage  ( aCommit.getShortMessage().trim() )
                .fullMessage   ( aCommit.getFullMessage().trim() )
                .author        ( getPerson(aCommit.getAuthorIdent()))
                .commiter      ( getPerson(aCommit.getCommitterIdent()))
                .ageFormatted  ( formatAge(currentTimeMillis(), toDate(aCommit.getCommitTime())))
                .build();
    }

    private static Date toDate(int aEpochSeconds) {
        return new Date(aEpochSeconds * 1000L);
    }

    private static String getPerson(PersonIdent aIdent) {
        return Optional.ofNullable(aIdent)
                .orElseGet(() -> new PersonIdent("<unknown>", "<no-email>"))
                .getName();
    }
}
