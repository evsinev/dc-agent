package com.payneteasy.dcagent.core.util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static com.payneteasy.dcagent.core.util.SafeFiles.createFileGuarded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SafeFilesTest {

    @Test
    public void create_file_guarded() throws IOException {
        assertThat(createFileGuarded(new File("./target"), "test.txt"))
                .isEqualTo(new File("./target/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "/test.txt"))
                .isEqualTo(new File("./target/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "dir1/test.txt"))
                .isEqualTo(new File("./target/dir1/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "/dir1/test.txt"))
                .isEqualTo(new File("./target/dir1/test.txt"));

        assertThat(createFileGuarded(new File("./target"), "dir1/dir2/../test.txt").getCanonicalFile())
                .isEqualTo(new File("./target/dir1/test.txt").getCanonicalFile());

        assertThat(createFileGuarded(new File("./target"), "dir1/dir2/dir3/../../test.txt").getCanonicalFile())
                .isEqualTo(new File("./target/dir1/test.txt").getCanonicalFile());

        assertThatThrownBy(() -> createFileGuarded(new File("./target"), "../test.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal attempt detected:");
    }
}
