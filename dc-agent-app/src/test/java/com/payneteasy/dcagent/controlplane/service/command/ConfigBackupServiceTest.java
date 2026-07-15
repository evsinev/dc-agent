package com.payneteasy.dcagent.controlplane.service.command;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ConfigFileEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigBackupServiceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private void write(String name, String content) throws Exception {
        Files.write(new File(folder.getRoot(), name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void lists_config_files_sorted_by_name_with_content() throws Exception {
        write("web.json", "{\"port\":80}");
        write("billing.json", "{\"type\":\"JAR\"}");

        List<ConfigFileEntry> files = new ConfigBackupService(folder.getRoot()).listConfigFiles();

        assertThat(files).extracting(ConfigFileEntry::getName).containsExactly("billing.json", "web.json");
        assertThat(files.get(0).getContent()).isEqualTo("{\"type\":\"JAR\"}");
    }

    @Test
    public void ignores_subdirectories() throws Exception {
        write("real.json", "{}");
        assertThat(new File(folder.getRoot(), "subdir").mkdir()).isTrue();

        assertThat(new ConfigBackupService(folder.getRoot()).listConfigFiles())
                .extracting(ConfigFileEntry::getName)
                .containsExactly("real.json");
    }

    @Test
    public void returns_empty_when_directory_has_no_files() {
        assertThat(new ConfigBackupService(folder.getRoot()).listConfigFiles()).isEmpty();
    }
}
