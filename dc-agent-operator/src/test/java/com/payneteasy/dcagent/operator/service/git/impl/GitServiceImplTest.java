package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.git.IGitService;
import com.payneteasy.dcagent.operator.service.git.messages.GitLogResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitPullResponse;
import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Date;

import static org.junit.Assume.assumeTrue;

public class GitServiceImplTest {

    private static final Logger LOG = LoggerFactory.getLogger( GitServiceImplTest.class );

    @BeforeClass
    public static void condition() {
        assumeTrue(System.getenv("GIT_REPO") != null);
    }

    @Test
    public void test_raw() throws IOException, GitAPIException {
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(new File(System.getenv("GIT_REPO"), ".git"))
                .findGitDir()
                .build();

        LOG.debug("repository = {}", repository.getBranch());
        Git git = new Git(repository);
        Iterable<RevCommit> commits = git.log().call();
        for (RevCommit commit : commits) {
            LOG.debug("{} {}", new Date(commit.getCommitTime() * 1000L), commit.getShortMessage());
        }
    }

    @Test
    public void service() {
        IGitService git = new GitServiceImpl(new File(System.getenv("GIT_REPO")));

        GitPullResponse pullResponse = git.pull(VoidRequest.VOID_REQUEST);
        LOG.debug("Pull response {}", pullResponse);

        GitLogResponse log = git.log(VoidRequest.VOID_REQUEST);
        LOG.debug("Git branch {}", log.getCurrentBranch());
        for (GitLogItem commit : log.getCommits()) {
            LOG.debug("commit = {}", commit);
        }
    }
}
