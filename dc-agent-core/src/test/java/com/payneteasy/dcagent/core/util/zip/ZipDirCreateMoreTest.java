package com.payneteasy.dcagent.core.util.zip;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ZipDirCreateMoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void zips_files_from_a_directory_tree_with_first_segment_prefix() throws Exception {
        File base = folder.newFolder("base");
        Files.write(new File(base, "top.txt").toPath(), "top".getBytes(StandardCharsets.UTF_8));
        File sub = new File(base, "sub");
        assertThat(sub.mkdirs()).isTrue();
        Files.write(new File(sub, "deep.txt").toPath(), "deep".getBytes(StandardCharsets.UTF_8));

        File zip = new File(folder.getRoot(), "out.zip");
        new ZipDirCreate().baseDir(base).firstSegment("root").createZipFile(zip);

        ZipFileViewer viewer = new ZipFileViewer(zip);
        assertThat(viewer.getItemText("root/top.txt")).isEqualTo("top");
        assertThat(viewer.getItemText("root/sub/deep.txt")).isEqualTo("deep");
    }

    @Test
    public void requires_a_base_dir() {
        File zip = new File(folder.getRoot(), "out.zip");

        assertThatThrownBy(() -> new ZipDirCreate().createZipFile(zip))
                .isInstanceOf(NullPointerException.class);
    }
}
