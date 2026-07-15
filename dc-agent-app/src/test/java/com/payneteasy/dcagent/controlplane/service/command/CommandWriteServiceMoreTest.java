package com.payneteasy.dcagent.controlplane.service.command;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CommandWriteServiceMoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final ApiKeyOps NO_KEYS = ApiKeyOps.builder().keep(java.util.List.of()).add(java.util.List.of()).build();

    private CommandWriteService service() {
        return new CommandWriteService(folder.getRoot(), new Gson());
    }

    private void write(String name, String json) throws Exception {
        Files.write(new File(folder.getRoot(), name).toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void get_command_of_missing_file_is_null() {
        assertThat(service().getCommand("absent")).isNull();
    }

    @Test
    public void get_command_parses_type_and_parameters() throws Exception {
        write("billing.json", "{\"type\":\"JAR\",\"jarFilename\":\"/srv/app.jar\"}");

        CommandDetail detail = service().getCommand("billing");

        assertThat(detail.getType()).isEqualTo(TaskType.JAR);
        assertThat(detail.getParameters()).containsEntry("jarFilename", "/srv/app.jar");
    }

    @Test
    public void get_command_masks_api_keys() throws Exception {
        write("billing.json", "{\"type\":\"JAR\",\"apiKeys\":{\"secret-key\":\"owner\"}}");

        assertThat(service().getCommand("billing").getApiKeys()).hasSize(1);
    }

    @Test
    public void create_over_existing_file_is_a_conflict() throws Exception {
        write("billing.json", "{\"type\":\"JAR\"}");

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", NO_KEYS, keys -> Map.of("type", "JAR"));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.CONFLICT);
    }

    @Test
    public void update_of_missing_file_is_not_found() {
        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.UPDATE, "absent", NO_KEYS, keys -> Map.of("type", "JAR"));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.NOT_FOUND);
    }

    @Test
    public void create_writes_the_config_file_and_returns_created() {
        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", NO_KEYS,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app.jar"));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.CREATED);
        assertThat(new File(folder.getRoot(), "billing.json")).exists();
    }

    @Test
    public void fetch_url_command_must_be_named_fetch_url() {
        assertThatThrownBy(() -> service().save(
                TaskType.FETCH_URL, CommandWriteService.Mode.CREATE, "wrong-name", NO_KEYS, keys -> Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void docker_command_must_be_named_dc_docker() {
        assertThatThrownBy(() -> service().save(
                TaskType.DOCKER, CommandWriteService.Mode.CREATE, "wrong-name", NO_KEYS, keys -> Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
