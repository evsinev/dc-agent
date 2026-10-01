package com.payneteasy.dcagent.controlplane;

import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService;
import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.modules.zipversion.ZipArchiveVersionSettings;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandSaveResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandZipArchiveVersionRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The control plane writes a zip-archive-version command only when a call would accept its config. */
public class ZipArchiveVersionCommandWriteTest {

    private static final Duration IDLE = Duration.ofMinutes(10);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path                                 configDir;
    private DcAgentControlPlaneRemoteServiceImpl controlPlane;

    @Before
    public void setUp() throws IOException {
        configDir    = tmp.newFolder("config").toPath();
        controlPlane = new DcAgentControlPlaneRemoteServiceImpl(null, null, null,
                new CommandWriteService(configDir.toFile(), Gsons.PRETTY_GSON), null, null, IDLE);
    }

    private static TZipArchiveVersionConfig.TZipArchiveVersionConfigBuilder config() {
        return TZipArchiveVersionConfig.builder()
                .dir("/opt/app/bundles")
                .versionFile("/opt/app/bundles/current")
                .reloadUrl("http://127.0.0.1:8080/reload?version=${version}")
                .reloadHeaders(Map.of("Authorization", "Bearer SERVICESECRET"))
                .waitTimeout("6m");
    }

    private static CommandZipArchiveVersionRequest request(TZipArchiveVersionConfig aConfig) {
        return CommandZipArchiveVersionRequest.builder().name("bundle").config(aConfig)
                .apiKeys(ApiKeyOps.builder().keep(List.of()).add(List.of(NewApiKey.builder().key("ci-key").owner("ci").build())).build())
                .build();
    }

    @Test
    public void a_valid_config_is_written_with_its_type_and_loads_back() {
        CommandSaveResponse response = controlPlane.createZipArchiveVersion(request(config().build()));

        assertThat(response.getStatus()).isEqualTo(CommandSaveStatus.CREATED);
        assertThat(response.getCommand().getType()).isEqualTo(TaskType.ZIP_ARCHIVE_VERSION);
        TZipArchiveVersionConfig loaded = new ConfigServiceImpl(configDir.toFile(), Gsons.PRETTY_GSON).getZipArchiveVersionConfig("bundle");
        assertThat(loaded.getType()).isEqualTo(TaskType.ZIP_ARCHIVE_VERSION);
        assertThat(loaded.getApiKeys()).containsEntry("ci-key", "ci");
        assertThat(ZipArchiveVersionSettings.from("bundle", loaded, IDLE).waitTimeout()).isEqualTo(Duration.ofMinutes(6));
    }

    @Test
    public void an_existing_command_is_updated_and_an_invalid_update_leaves_its_file_as_it_was() throws IOException {
        controlPlane.createZipArchiveVersion(request(config().build()));

        CommandSaveResponse updated = controlPlane.updateZipArchiveVersion(request(config().waitTimeout("7m").build()));
        assertThat(updated.getStatus()).isEqualTo(CommandSaveStatus.UPDATED);
        String written = Files.readString(configDir.resolve("bundle.json"));
        assertThat(written).contains("\"waitTimeout\": \"7m\"");

        CommandSaveResponse invalid = controlPlane.updateZipArchiveVersion(request(config().maxBytes("-1").build()));
        assertThat(invalid.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(invalid.getMessage()).startsWith("config bundle: field maxBytes:");
        assertThat(Files.readString(configDir.resolve("bundle.json"))).isEqualTo(written);
    }

    @Test
    public void an_invalid_config_is_refused_as_data_naming_the_field_and_nothing_is_written() {
        CommandSaveResponse create = controlPlane.createZipArchiveVersion(request(config().waitTimeout("5x").build()));
        CommandSaveResponse update = controlPlane.updateZipArchiveVersion(request(config()
                .reloadHeaders(Map.of("Authorization", "Bearer SERVICESECRET\u000b")).build()));

        assertThat(create.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(create.getMessage()).startsWith("config bundle: field waitTimeout:");
        assertThat(update.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(update.getMessage()).contains("reloadHeaders").doesNotContain("SERVICESECRET");
        assertThat(configDir).isEmptyDirectory();
    }
}
