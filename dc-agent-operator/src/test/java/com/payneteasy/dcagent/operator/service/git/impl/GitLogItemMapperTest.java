package com.payneteasy.dcagent.operator.service.git.impl;

import com.payneteasy.dcagent.operator.service.git.model.GitLogItem;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class GitLogItemMapperTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private RevCommit commit;

    @Before
    public void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(folder.getRoot()).call()) {
            Files.write(new File(folder.getRoot(), "file.txt").toPath(), "hi".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("file.txt").call();
            commit = git.commit()
                    .setMessage("initial commit")
                    .setAuthor("Test Author", "author@example.com")
                    .setCommitter("Test Committer", "committer@example.com")
                    .call();
        }
    }

    @Test
    public void maps_short_message() {
        assertThat(GitLogItemMapper.of(commit).getShortMessage()).isEqualTo("initial commit");
    }

    @Test
    public void maps_author_name() {
        assertThat(GitLogItemMapper.of(commit).getAuthor()).isEqualTo("Test Author");
    }

    @Test
    public void maps_committer_name() {
        assertThat(GitLogItemMapper.of(commit).getCommiter()).isEqualTo("Test Committer");
    }

    @Test
    public void formats_the_commit_date() {
        GitLogItem item = GitLogItemMapper.of(commit);

        assertThat(item.getDateFormatted()).isNotBlank();
        assertThat(item.getAgeFormatted()).isNotBlank();
    }
}
