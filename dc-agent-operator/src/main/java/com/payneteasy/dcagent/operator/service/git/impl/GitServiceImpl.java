package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.git.IGitService;
import com.payneteasy.dcagent.operator.service.git.messages.GitLogResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitPullResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitStatusResponse;
import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class GitServiceImpl implements IGitService {

    private static final Logger LOG = LoggerFactory.getLogger( GitServiceImpl.class );

    private final Repository repository;
    private final Git        git;
    private final SafeGit    safeGit;

    public GitServiceImpl(File aRepositoryDir) {
        try {
            repository = new FileRepositoryBuilder()
                    .setGitDir(new File(aRepositoryDir, ".git"))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open git repo at " + aRepositoryDir.getAbsolutePath(), e);
        }

        git = new Git(repository);
        safeGit = new SafeGit(repository, git);

        LOG.info("Fetching repo status from {}...", aRepositoryDir.getAbsolutePath());
        LOG.info("Repo status = {}", status(VoidRequest.VOID_REQUEST));
    }

    @Override
    public GitLogResponse log(VoidRequest aRequest) {
        List<GitLogItem> commits = safeGit.getCommits(20);

        GitLogItem lastCommit = commits
                .stream()
                .findFirst()
                .orElse(GitLogItem.EMPTY);

        return GitLogResponse.builder()
                .currentBranch ( safeGit.getBranchName() )
                .commits       ( commits)
                .lastCommit    ( lastCommit)
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

    @Override
    public GitStatusResponse status(VoidRequest aRequest) {
        GitLogItem item = safeGit.getCommits(1)
                .stream()
                .findFirst()
                .orElse(GitLogItem.EMPTY);

        return GitStatusResponse.builder()
                .currentBranch ( safeGit.getBranchName())
                .lastCommit    ( item )
                .build();
    }

}
