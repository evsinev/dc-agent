package com.payneteasy.dcagent.controlplane.service.command;

import com.payneteasy.dcagent.TestConfigs;

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
        return keys -> TJarConfig.builder().type(TaskType.JAR).jarFilename(aJarFilename).serviceName("svc").serviceDir(TestConfigs.serviceDir("svc")).apiKeys(keys).build();
    }

    private static String read(Path aDir, String aName) throws Exception {
        return new String(Files.readAllBytes(aDir.resolve(aName)), UTF_8);
    }

    @Test
    public void create_writes_typed_json_with_type_and_masks_secret_in_the_response() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        CommandSaveResult result = svc.save(TaskType.JAR, Mode.CREATE, "billing",
                add(key("secret-alpha-0123456789abcdef0123456789", "gitlab-ci")), jar("/srv/app/app.jar"));

        assertEquals(CommandSaveStatus.CREATED, result.getStatus());

        String onDisk = read(dir, "billing.json");
        assertTrue(onDisk.contains("\"type\": \"JAR\""));
        assertTrue(onDisk.contains("/srv/app/app.jar"));
        // only the hash is stored, never the key itself
        assertFalse(onDisk.contains("secret-alpha-0123456789abcdef0123456789"));
        assertTrue(onDisk.contains(TestConfigs.hash("secret-alpha-0123456789abcdef0123456789")));

        CommandDetail detail = result.getCommand();
        assertEquals(TaskType.JAR, detail.getType());
        assertEquals("/srv/app/app.jar", detail.getParameters().get("jarFilename"));
        assertEquals(1, detail.getApiKeys().size());
        assertEquals("gitlab-ci", detail.getApiKeys().get(0).getOwner());
        assertTrue(detail.getApiKeys().get(0).getMaskedId().startsWith("****"));
        // the secret never appears in the response detail
        assertFalse(detail.toString().contains("secret-alpha-0123456789abcdef0123456789"));
    }

    @Test
    public void create_conflicts_when_a_config_already_exists() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        svc.save(TaskType.JAR, Mode.CREATE, "billing", add(key("s-0123456789abcdef0123456789abcdef", "o")), jar("/srv/a/a.jar"));
        CommandSaveResult again = svc.save(TaskType.JAR, Mode.CREATE, "billing", add(key("s2-0123456789abcdef0123456789abcdef", "o")), jar("/srv/b/b.jar"));

        assertEquals(CommandSaveStatus.CONFLICT, again.getStatus());
        assertNull(again.getCommand());
        assertTrue(read(dir, "billing.json").contains("/a.jar")); // original untouched
    }

    @Test
    public void create_conflicts_with_an_existing_yaml_config() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        Files.write(dir.resolve("billing.yml"), "dir: /opt/x\n".getBytes(UTF_8));
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        CommandSaveResult result = svc.save(TaskType.JAR, Mode.CREATE, "billing", add(key("s-0123456789abcdef0123456789abcdef", "o")), jar("/srv/a/a.jar"));
        assertEquals(CommandSaveStatus.CONFLICT, result.getStatus());
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixed_name_types_reject_a_different_name() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);
        svc.save(TaskType.FETCH_URL, Mode.CREATE, "not-fetch-url", add(key("s-0123456789abcdef0123456789abcdef", "o")), keys -> keys);
    }

    @Test
    public void rejects_a_name_outside_the_command_name_rule_and_writes_nothing() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);
        for (String name : new String[]{"../evil", "a..b", "a/b", "a b", ""}) {
            try {
                svc.save(TaskType.JAR, Mode.CREATE, name, add(key("s-0123456789abcdef0123456789abcdef", "o")), jar("/srv/a/a.jar"));
                throw new AssertionError("accepted " + name);
            } catch (IllegalArgumentException expected) {
                // the CommandNames rule, before any file is resolved
            }
        }
        assertEquals(0, dir.toFile().list().length);
    }

    @Test
    public void accepts_names_the_operator_accepts() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);
        for (String name : new String[]{"_app", ".app", "a-b.c_d"}) {
            assertEquals(CommandSaveStatus.CREATED,
                    svc.save(TaskType.JAR, Mode.CREATE, name, add(key("s-0123456789abcdef0123456789abcdef", "o")), jar("/srv/a/a.jar")).getStatus());
        }
    }

    @Test
    public void update_missing_command_returns_not_found() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);
        CommandSaveResult result = svc.save(TaskType.JAR, Mode.UPDATE, "ghost", add(key("s-0123456789abcdef0123456789abcdef", "o")), jar("/srv/a/a.jar"));
        assertEquals(CommandSaveStatus.NOT_FOUND, result.getStatus());
    }

    @Test
    public void update_keeps_matched_keys_adds_new_and_drops_the_rest() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        svc.save(TaskType.JAR, Mode.CREATE, "billing",
                add(key("secret-alpha-0123456789abcdef0123456789", "alpha"), key("secret-beta-0123456789abcdef0123456789", "beta")), jar("/srv/app/old.jar"));

        // keep alpha (by its masked id), drop beta, add gamma; also change jarFilename.
        String keepAlpha = CommandWriteService.maskedId("secret-alpha-0123456789abcdef0123456789");
        ApiKeyOps ops = ApiKeyOps.builder()
                .keep(List.of(keepAlpha))
                .add(List.of(key("secret-gamma-0123456789abcdef0123456789", "gamma")))
                .build();

        CommandSaveResult result = svc.save(TaskType.JAR, Mode.UPDATE, "billing", ops, jar("/srv/app/new.jar"));
        assertEquals(CommandSaveStatus.UPDATED, result.getStatus());

        List<String> owners = result.getCommand().getApiKeys().stream()
                .map(k -> k.getOwner()).sorted().toList();
        assertEquals(List.of("alpha", "gamma"), owners);

        String onDisk = read(dir, "billing.json");
        assertTrue(onDisk.contains("/srv/app/new.jar"));
        assertTrue(onDisk.contains(TestConfigs.hash("secret-alpha-0123456789abcdef0123456789")));
        assertTrue(onDisk.contains(TestConfigs.hash("secret-gamma-0123456789abcdef0123456789")));
        assertFalse(onDisk.contains(TestConfigs.hash("secret-beta-0123456789abcdef0123456789"))); // dropped
        assertFalse(onDisk.contains("secret-"));
    }

    @Test
    public void update_migrates_a_yaml_config_to_json() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        Files.write(dir.resolve("archive.yml"), "type: ZIP_ARCHIVE\ndir: /opt/old\napiKeys:\n  secret-legacy: ci\n".getBytes(UTF_8));
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

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
        // ... as its hash, under the same masked id
        String onDisk = read(dir, "archive.json");
        assertFalse(onDisk.contains("secret-legacy"));
        assertTrue(onDisk.contains(TestConfigs.hash("secret-legacy")));
        assertEquals(keepLegacy, result.getCommand().getApiKeys().get(0).getMaskedId());
    }

    @Test
    public void get_returns_null_for_a_missing_command() throws Exception {
        Path dir = Files.createTempDirectory("cmd-write");
        CommandWriteService svc = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);
        assertNull(svc.getCommand("nope"));
    }
}
