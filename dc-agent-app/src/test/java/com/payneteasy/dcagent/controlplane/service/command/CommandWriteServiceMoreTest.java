package com.payneteasy.dcagent.controlplane.service.command;

import com.payneteasy.dcagent.TestConfigs;

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

    private static final ApiKeyOps NO_KEYS  = ApiKeyOps.builder().keep(java.util.List.of()).add(java.util.List.of()).build();
    private static final ApiKeyOps ONE_KEY  = ApiKeyOps.builder().keep(java.util.List.of())
            .add(java.util.List.of(com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey.builder()
                    .key("a-new-key-0123456789abcdef0123456789").owner("ci").build()))
            .build();

    private CommandWriteService service() {
        return new CommandWriteService(folder.getRoot(), new Gson(), TestConfigs.VALIDATOR);
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
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.CREATED);
        assertThat(new File(folder.getRoot(), "billing.json")).exists();
    }

    @Test
    public void a_config_the_agent_would_refuse_is_invalid_and_not_written() {
        // twin of create_writes_the_config_file_and_returns_created, without the key
        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", NO_KEYS,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).isEqualTo("\"apiKeys\" is empty");
        assertThat(new File(folder.getRoot(), "billing.json")).doesNotExist();
    }

    @Test
    public void an_invalid_update_leaves_the_existing_file_as_it_was() throws Exception {
        String before = "{\"type\":\"JAR\",\"apiKeys\":{\"k\":\"ci\"},\"jarFilename\":\"/srv/app.jar\"}";
        write("billing.json", before);

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.UPDATE, "billing", NO_KEYS,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(new File(folder.getRoot(), "billing.json")).hasContent(before);
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

    @Test
    public void a_config_larger_than_the_loader_reads_is_invalid_and_not_written() {
        // twin: create_writes_the_config_file_and_returns_created
        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys,
                        "waitUrl", "x".repeat((int) com.payneteasy.dcagent.core.config.validation.CommandConfigFile.MAX_BYTES)));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).contains("larger than");
        assertThat(new File(folder.getRoot(), "billing.json")).doesNotExist();
    }

    @Test
    public void an_update_through_a_symbolic_link_is_invalid_and_the_link_stays_untouched() throws Exception {
        String source = "{\"type\":\"JAR\",\"apiKeys\":{\"k\":\"ci\"},\"jarFilename\":\"/srv/app.jar\"}";
        write(".billing-source", source);
        java.nio.file.Path link = folder.getRoot().toPath().resolve("billing.json");
        Files.createSymbolicLink(link, folder.getRoot().toPath().resolve(".billing-source"));

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.UPDATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).contains("symbolic link");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(new File(folder.getRoot(), ".billing-source")).hasContent(source);
    }

    @Test
    public void a_link_out_of_the_config_dir_is_invalid_not_a_500() throws Exception {
        java.nio.file.Path outside = java.nio.file.Files.createTempFile("site-source", ".json");
        try {
            String source = "{\"type\":\"JAR\",\"apiKeys\":{\"k\":\"ci\"},\"jarFilename\":\"/srv/app.jar\"}";
            Files.writeString(outside, source);
            java.nio.file.Path link = folder.getRoot().toPath().resolve("billing.json");
            Files.createSymbolicLink(link, outside);

            CommandWriteService.CommandSaveResult result = service().save(
                    TaskType.JAR, CommandWriteService.Mode.UPDATE, "billing", ONE_KEY,
                    keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

            assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
            assertThat(Files.isSymbolicLink(link)).isTrue();
            assertThat(outside).hasContent(source);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void a_path_out_of_command_dirs_is_invalid_and_not_written() {
        // twin: create_writes_the_config_file_and_returns_created (/srv/app/app.jar)
        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/etc/cron.d/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).contains("must lie inside one of COMMAND_DIRS");
        assertThat(new File(folder.getRoot(), "billing.json")).doesNotExist();
    }

    @Test
    public void a_new_key_shorter_than_32_characters_is_invalid() {
        ApiKeyOps shortKey = ApiKeyOps.builder().keep(java.util.List.of())
                .add(java.util.List.of(com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey.builder()
                        .key("0123456789abcdef0123456789abcde").owner("ci").build()))
                .build();

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", shortKey,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).contains("at least 32");
        assertThat(new File(folder.getRoot(), "billing.json")).doesNotExist();
    }

    @Test
    public void an_update_of_a_config_with_a_duplicate_key_is_invalid_and_names_no_key() throws Exception {
        String before = "{\"type\":\"JAR\",\"apiKeys\":{\"secret-dup-0123456789abcdef0123\":\"ci\",\"secret-dup-0123456789abcdef0123\":\"ci\"},"
                + "\"jarFilename\":\"/srv/app/app.jar\",\"serviceName\":\"billing\",\"serviceDir\":\"" + TestConfigs.serviceDir("billing") + "\"}";
        write("billing.json", before);

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.UPDATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).isEqualTo("billing.json: apiKeys.<key>: duplicate key");
        assertThat(new File(folder.getRoot(), "billing.json")).hasContent(before);
    }

    @Test
    public void an_update_with_a_json_and_yaml_pair_is_invalid_and_both_stay() throws Exception {
        String json = "{\"type\":\"JAR\",\"apiKeys\":{\"k\":\"ci\"},\"jarFilename\":\"/srv/app/app.jar\",\"serviceName\":\"billing\",\"serviceDir\":\"" + TestConfigs.serviceDir("billing") + "\"}";
        write("billing.json", json);
        write("billing.yml", "type: JAR\napiKeys:\n  k: ci\n");

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.UPDATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
        assertThat(result.getMessage()).contains("both exist");
        assertThat(new File(folder.getRoot(), "billing.json")).hasContent(json);
        assertThat(new File(folder.getRoot(), "billing.yml")).exists();
    }

    @Test
    public void a_create_over_a_json_and_yaml_pair_is_invalid_not_a_conflict() throws Exception {
        write("billing.json", "{}");
        write("billing.yml", "type: JAR\n");

        CommandWriteService.CommandSaveResult result = service().save(
                TaskType.JAR, CommandWriteService.Mode.CREATE, "billing", ONE_KEY,
                keys -> Map.of("type", "JAR", "jarFilename", "/srv/app/app.jar", "serviceName", "billing", "serviceDir", TestConfigs.serviceDir("billing"), "apiKeys", keys));

        assertThat(result.getStatus()).isEqualTo(CommandSaveStatus.INVALID);
    }
}
