package com.payneteasy.dcagent.core.config.service.impl;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.config.validation.TestValidators;
import com.payneteasy.dcagent.core.config.validation.ConfigProblemException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ConfigServiceImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ConfigServiceImpl service() {
        Gson gson = new Gson();
        return new ConfigServiceImpl(folder.getRoot(), gson, TestValidators.validator(gson));
    }

    private void write(String name, String content) throws Exception {
        Files.write(new File(folder.getRoot(), name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void loads_config_from_json_file() throws Exception {
        write("fetch-url.json", "{\"type\":\"FETCH_URL\",\"apiKeys\":{\"k\":\"v\"}}");

        TFetchUrlConfig config = service().getFetchUrlConfig();

        assertThat(config.getApiKeys()).containsEntry("k", "v");
        assertThat(config.getType()).isEqualTo(TaskType.FETCH_URL);
    }

    @Test
    public void loads_config_from_yaml_file() throws Exception {
        write("myzip.yml", "type: ZIP_DIRS\napiKeys:\n  k: v\ndir: /opt/zip-dir\ndelete: true\n");

        TZipDirsConfig config = service().getZipDirsConfig("myzip");

        assertThat(config.getDir()).isEqualTo("/opt/zip-dir");
        // a YAML scalar is a string after the conversion; Gson still reads it into the boolean
        assertThat(config.isDelete()).isTrue();
    }

    @Test
    public void json_and_yaml_of_one_command_are_refused_not_resolved() throws Exception {
        write("myzip.json", "{\"type\":\"ZIP_DIRS\",\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/from-json\"}");
        write("myzip.yml", "type: ZIP_DIRS\napiKeys:\n  k: v\ndir: /from-yaml\n");

        assertThatThrownBy(() -> service().getZipDirsConfig("myzip"))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageContaining("both exist");
    }

    @Test
    public void config_without_type_is_refused() throws Exception {
        write("za.json", "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/opt/archive\"}");
        write("nt.json", "{\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/opt/archive\"}");

        assertThat(service().getZipArchiveConfig("za").getDir()).isEqualTo("/opt/archive");
        assertThatThrownBy(() -> service().getZipArchiveConfig("nt"))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageContaining("no \"type\"");
    }

    @Test
    public void config_with_a_mistyped_type_is_refused() throws Exception {
        write("bad.json", "{\"type\":\"ZIP_ARHCIVE\",\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/opt/archive\"}");

        assertThatThrownBy(() -> service().getZipArchiveConfig("bad"))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageContaining("unknown \"type\"");
    }

    @Test
    public void a_symbolic_link_config_is_refused() throws Exception {
        write("target.txt", "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/opt/archive\"}");
        Path link = folder.getRoot().toPath().resolve("za.json");
        Files.createSymbolicLink(link, folder.getRoot().toPath().resolve("target.txt"));

        assertThatThrownBy(() -> service().getZipArchiveConfig("za"))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    public void missing_config_throws_illegal_state() {
        assertThatThrownBy(() -> service().getJarConfig("absent"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void a_name_out_of_config_dir_is_refused_before_any_file_is_read() {
        assertThatThrownBy(() -> service().getJarConfig("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void loads_docker_service_config() throws Exception {
        write("dc-docker.json", "{\"type\":\"DOCKER\",\"apiKeys\":{\"k\":\"v\"}}");

        TDockerConfig config = service().getServiceConfig("dc-docker");

        assertThat(config.getApiKeys()).containsEntry("k", "v");
    }

    @Test
    public void docker_type_under_another_name_is_refused() throws Exception {
        write("svc.json", "{\"type\":\"DOCKER\",\"apiKeys\":{\"k\":\"v\"}}");

        assertThatThrownBy(() -> service().getServiceConfig("svc"))
                .isInstanceOf(ConfigProblemException.class)
                .hasMessageContaining("dc-docker");
    }

    @Test
    public void loads_zip_archive_config() throws Exception {
        write("za.json", "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/opt/archive\"}");

        TZipArchiveConfig config = service().getZipArchiveConfig("za");

        assertThat(config.getDir()).isEqualTo("/opt/archive");
    }

    @Test
    public void loads_save_artifact_config() throws Exception {
        write("sa.json", "{\"type\":\"SAVE_ARTIFACT\",\"apiKeys\":{\"k\":\"v\"},\"dir\":\"/opt/a\",\"extension\":\"tgz\"}");

        TSaveArtifactConfig config = service().getSaveArtifactConfig("sa");

        assertThat(config.getExtension()).isEqualTo("tgz");
    }
}
