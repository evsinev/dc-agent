package com.payneteasy.dcagent.core.config.service.impl;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.config.model.TFetchUrlConfig;
import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TZipDirsConfig;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ConfigServiceImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ConfigServiceImpl service() {
        return new ConfigServiceImpl(folder.getRoot(), new Gson());
    }

    private void write(String name, String content) throws Exception {
        Files.write(new File(folder.getRoot(), name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void loads_config_from_json_file() throws Exception {
        write("fetch-url.json", "{\"apiKeys\":{\"k\":\"v\"}}");

        TFetchUrlConfig config = service().getFetchUrlConfig();

        assertThat(config.getApiKeys()).containsEntry("k", "v");
    }

    @Test
    public void loads_config_from_yaml_file() throws Exception {
        write("myzip.yml", "dir: /tmp/zip-dir\n");

        TZipDirsConfig config = service().getZipDirsConfig("myzip");

        assertThat(config.getDir()).isEqualTo("/tmp/zip-dir");
    }

    @Test
    public void prefers_json_over_yaml_when_both_present() throws Exception {
        write("myzip.json", "{\"dir\":\"/from-json\"}");
        write("myzip.yml", "dir: /from-yaml\n");

        assertThat(service().getZipDirsConfig("myzip").getDir()).isEqualTo("/from-json");
    }

    @Test
    public void missing_config_throws_illegal_state() {
        assertThatThrownBy(() -> service().getJarConfig("absent"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void loads_docker_service_config() throws Exception {
        write("svc.json", "{\"apiKeys\":{\"k\":\"v\"}}");

        TDockerConfig config = service().getServiceConfig("svc");

        assertThat(config.getApiKeys()).containsEntry("k", "v");
    }

    @Test
    public void loads_zip_archive_config() throws Exception {
        write("za.json", "{\"dir\":\"/archive\"}");

        TZipArchiveConfig config = service().getZipArchiveConfig("za");

        assertThat(config.getDir()).isEqualTo("/archive");
    }

    @Test
    public void loads_save_artifact_config() throws Exception {
        write("sa.json", "{\"extension\":\"tgz\"}");

        TSaveArtifactConfig config = service().getSaveArtifactConfig("sa");

        assertThat(config.getExtension()).isEqualTo("tgz");
    }
}
