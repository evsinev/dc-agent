package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class SafeGit {

    private final Repository repository;
    private final Git        git;

    public SafeGit(Repository repository, Git git) {
        this.repository = repository;
        this.git        = git;
    }

    List<GitLogItem> getCommits(int aCount) {
        Iterable<RevCommit> commits;
        try {
            commits = git.log().call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot execute git log", e);
        }

        return StreamSupport.stream(commits.spliterator(), false)
                .map(GitLogItemMapper::of)
                .limit(aCount)
                .collect(Collectors.toList());
    }

    String getBranchName() {
        try {
            return repository.getBranch();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get branch name", e);
        }
    }

}
