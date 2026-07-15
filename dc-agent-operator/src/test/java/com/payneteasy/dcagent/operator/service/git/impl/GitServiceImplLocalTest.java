package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.git.messages.GitLogResponse;
import com.payneteasy.dcagent.operator.service.git.messages.GitStatusResponse;
import org.eclipse.jgit.api.Git;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises GitServiceImpl (and SafeGit) over a local file:// remote — no SSH, no external repo.
 */
public class GitServiceImplLocalTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File work;
    private File home;
    private File ssh;

    @Before
    public void setUp() throws Exception {
        File remote = folder.newFolder("remote");
        try (Git git = Git.init().setDirectory(remote).call()) {
            Files.write(new File(remote, "readme.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("readme.txt").call();
            git.commit()
                    .setMessage("initial commit")
                    .setAuthor("Author", "author@example.com")
                    .setCommitter("Author", "author@example.com")
                    .call();
        }

        work = folder.newFolder("work");
        try (Git cloned = Git.cloneRepository()
                .setURI(remote.toURI().toString())
                .setDirectory(work)
                .call()) {
            // just clone; GitServiceImpl reopens the repo
        }

        home = folder.newFolder("home");
        ssh = folder.newFolder("ssh");
    }

    private GitServiceImpl service() {
        return new GitServiceImpl(work, home, ssh, false);
    }

    @Test
    public void log_returns_commits_and_branch() {
        GitLogResponse response = service().log(VoidRequest.VOID_REQUEST);

        assertThat(response.getCommits()).isNotEmpty();
        assertThat(response.getCurrentBranch()).isNotBlank();
    }

    @Test
    public void log_last_commit_carries_the_message() {
        GitLogResponse response = service().log(VoidRequest.VOID_REQUEST);

        assertThat(response.getLastCommit().getShortMessage()).isEqualTo("initial commit");
    }

    @Test
    public void status_returns_current_branch_and_last_commit() {
        GitStatusResponse status = service().status(VoidRequest.VOID_REQUEST);

        assertThat(status.getCurrentBranch()).isNotBlank();
        assertThat(status.getLastCommit()).isNotNull();
    }

    @Test
    public void pull_from_local_remote_is_successful() {
        assertThat(service().pull(VoidRequest.VOID_REQUEST).isSuccessful()).isTrue();
    }

    @Test
    public void constructor_can_read_status_on_startup() {
        assertThat(new GitServiceImpl(work, home, ssh, true)).isNotNull();
    }
}
