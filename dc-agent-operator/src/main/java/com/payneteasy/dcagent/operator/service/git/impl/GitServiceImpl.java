package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.git.IGitService;
import com.payneteasy.dcagent.operator.service.git.messages.GitLogResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitPullResponse;
import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class GitServiceImpl implements IGitService {

    private static final Logger LOG = LoggerFactory.getLogger( GitServiceImpl.class );

    private final Repository repository;
    private final Git        git;

    public GitServiceImpl(File aRepositoryDir) {
        try {
            repository = new FileRepositoryBuilder()
                    .setGitDir(new File(aRepositoryDir, ".git"))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open git repo at " + aRepositoryDir.getAbsolutePath(), e);
        }

        git = new Git(repository);
    }

    @Override
    public GitLogResponse log(VoidRequest aRequest) {
        return GitLogResponse.builder()
                .currentBranch(getBranchName())
                .commits(getCommits())
                .build();
    }

    @Override
    public GitPullResponse pull(VoidRequest aRequest) {
        try {
            PullCommand pull   = git.pull();
            PullResult  result = pull.call();
            return GitPullResponse.builder()
                    .successful(result.isSuccessful())
                    .build();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot pull. Did you forgot to set env variable GIT_SSH=/usr/bin/ssh", e);
        }
    }

    private List<GitLogItem> getCommits() {
        Iterable<RevCommit> commits;
        try {
            commits = git.log().call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot execute git log", e);
        }

        return StreamSupport.stream(commits.spliterator(), false)
                .map(GitLogItemMapper::of)
                .limit(21)
                .collect(Collectors.toList());
    }

    private String getBranchName() {
        try {
            return repository.getBranch();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get branch name", e);
        }
    }
}
