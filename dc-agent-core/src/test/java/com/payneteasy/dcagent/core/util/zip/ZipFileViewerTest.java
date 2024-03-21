package com.payneteasy.dcagent.core.util.zip;

import org.junit.Test;

import java.io.File;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;


public class ZipFileViewerTest {

    @Test
    public void read_zip_file() {
        ZipFileViewer viewer = new ZipFileViewer(new File("src/test/resources/dc-agent-docker-0.0.1.zip"));

        {
            ZipFileItem item = viewer.getItem("config-example.yml").orElseThrow(() -> new IllegalArgumentException("No entry config-example.yml"));
            assertThat(new String(item.getBytes(), UTF_8)).isEqualTo("description: just an example");
        }

        {
            ZipFileItem item = viewer.getItem("config/sample-app-2.yml").orElseThrow(() -> new IllegalArgumentException("No entry config/sample-app-2.yml"));
            assertThat(item.getBytes().length).isEqualTo(226);
        }

        assertThat(viewer.getItemText("config-example.yml")).isEqualTo("description: just an example");
    }
}