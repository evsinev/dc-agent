package com.payneteasy.dcagent.controlplane.service.command;

import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService.CommandSaveResult;
import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService.Mode;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.model.TZipArchiveConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ApiKeyOps;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandSaveStatus;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.NewApiKey;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CommandWriteServiceTest {

    private static ApiKeyOps add(NewApiKey... aKeys) {
        return ApiKeyOps.builder().keep(List.of()).add(List.of(aKeys)).build();
    }

    private static NewApiKey key(String aSecret, String aOwner) {
        return NewApiKey.builder().key(aSecret).owner(aOwner).build();
    }

    private static Function<java.util.Map<String, String>, Object> jar(String aJarFilename) {
        return keys -> TJarConfig.builder().type(TaskType.JAR).jarFilename(aJarFilename).serviceName("svc").apiKeys(keys).build();
    }

    private static String read(Path aDir, String aName) throws Exception {
        return new String(Files.readAllBytes(aDir.resolve(aName)), UTF_8);
    }

    @Test
    public void create_writes_typed_json_with_type_and_masks_secret_in_the_response() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);

        CommandSaveResult result = svc.save(TaskType.JAR, Mode.CREATE, "billing",
                add(key("secret-alpha", "gitlab-ci")), jar("/srv/app.jar"));

        assertEquals(CommandSaveStatus.CREATED, result.getStatus());

        String onDisk = read(dir, "billing.json");
        assertTrue(onDisk.contains("\"type\": \"JAR\""));
        assertTrue(onDisk.contains("/srv/app.jar"));
        assertTrue(onDisk.contains("secret-alpha")); // the secret is stored on disk

        CommandDetail detail = result.getCommand();
        assertEquals(TaskType.JAR, detail.getType());
        assertEquals("/srv/app.jar", detail.getParameters().get("jarFilename"));
        assertEquals(1, detail.getApiKeys().size());
        assertEquals("gitlab-ci", detail.getApiKeys().get(0).getOwner());
        assertTrue(detail.getApiKeys().get(0).getMaskedId().startsWith("****"));
        // the secret never appears in the response detail
        assertFalse(detail.toString().contains("secret-alpha"));
    }

    @Test
    public void create_conflicts_when_a_config_already_exists() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);

        svc.save(TaskType.JAR, Mode.CREATE, "billing", add(key("s", "o")), jar("/a.jar"));
        CommandSaveResult again = svc.save(TaskType.JAR, Mode.CREATE, "billing", add(key("s2", "o")), jar("/b.jar"));

        assertEquals(CommandSaveStatus.CONFLICT, again.getStatus());
        assertNull(again.getCommand());
        assertTrue(read(dir, "billing.json").contains("/a.jar")); // original untouched
    }

    @Test
    public void create_conflicts_with_an_existing_yaml_config() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        Files.write(dir.resolve("billing.yml"), "dir: /opt/x\n".getBytes(UTF_8));
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);

        CommandSaveResult result = svc.save(TaskType.JAR, Mode.CREATE, "billing", add(key("s", "o")), jar("/a.jar"));
        assertEquals(CommandSaveStatus.CONFLICT, result.getStatus());
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixed_name_types_reject_a_different_name() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);
        svc.save(TaskType.FETCH_URL, Mode.CREATE, "not-fetch-url", add(key("s", "o")), keys -> keys);
    }

    @Test(expected = SecurityException.class)
    public void rejects_path_traversal_in_the_name() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);
        svc.save(TaskType.JAR, Mode.CREATE, "../evil", add(key("s", "o")), jar("/a.jar"));
    }

    @Test
    public void update_missing_command_returns_not_found() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);
        CommandSaveResult result = svc.save(TaskType.JAR, Mode.UPDATE, "ghost", add(key("s", "o")), jar("/a.jar"));
        assertEquals(CommandSaveStatus.NOT_FOUND, result.getStatus());
    }

    @Test
    public void update_keeps_matched_keys_adds_new_and_drops_the_rest() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);

        svc.save(TaskType.JAR, Mode.CREATE, "billing",
                add(key("secret-alpha", "alpha"), key("secret-beta", "beta")), jar("/srv/old.jar"));

        // keep alpha (by its masked id), drop beta, add gamma; also change jarFilename.
        String keepAlpha = CommandWriteService.maskedId("secret-alpha");
        ApiKeyOps ops = ApiKeyOps.builder()
                .keep(List.of(keepAlpha))
                .add(List.of(key("secret-gamma", "gamma")))
                .build();

        CommandSaveResult result = svc.save(TaskType.JAR, Mode.UPDATE, "billing", ops, jar("/srv/new.jar"));
        assertEquals(CommandSaveStatus.UPDATED, result.getStatus());

        List<String> owners = result.getCommand().getApiKeys().stream()
                .map(k -> k.getOwner()).sorted().toList();
        assertEquals(List.of("alpha", "gamma"), owners);

        String onDisk = read(dir, "billing.json");
        assertTrue(onDisk.contains("/srv/new.jar"));
        assertTrue(onDisk.contains("secret-alpha"));
        assertTrue(onDisk.contains("secret-gamma"));
        assertFalse(onDisk.contains("secret-beta")); // dropped
    }

    @Test
    public void update_migrates_a_yaml_config_to_json() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        Files.write(dir.resolve("archive.yml"), "dir: /opt/old\napiKeys:\n  secret-legacy: ci\n".getBytes(UTF_8));
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);

        String keepLegacy = CommandWriteService.maskedId("secret-legacy");
        ApiKeyOps ops = ApiKeyOps.builder().keep(List.of(keepLegacy)).add(List.of()).build();

        CommandSaveResult result = svc.save(TaskType.ZIP_ARCHIVE, Mode.UPDATE, "archive", ops,
                keys -> TZipArchiveConfig.builder().type(TaskType.ZIP_ARCHIVE).dir("/opt/new").apiKeys(keys).build());

        assertEquals(CommandSaveStatus.UPDATED, result.getStatus());
        assertTrue(new File(dir.toFile(), "archive.json").exists());
        assertFalse(new File(dir.toFile(), "archive.yml").exists()); // legacy file removed
        assertEquals("/opt/new", result.getCommand().getParameters().get("dir"));
        // the kept legacy secret survived the migration
        assertEquals(1, result.getCommand().getApiKeys().size());
        assertEquals("ci", result.getCommand().getApiKeys().get(0).getOwner());
    }

    @Test
    public void get_returns_null_for_a_missing_command() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON);
        assertNull(svc.getCommand("nope"));
    }
}
