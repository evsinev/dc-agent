package com.payneteasy.dcagent.controlplane.service.command;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandDetail;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.CommandInfoItem;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class CommandListServiceTest {

    @Test
    public void lists_non_docker_commands_with_parameters_and_masks_secrets() throws Exception {
        Path dir = Files.createTempDirectory("command-config");
        write(dir, "web.json",
                "{ \"jarFilename\": \"web.jar\", \"serviceName\": \"web\", \"apiKeys\": { \"SUPERSECRET\": \"gitlab-ci\" } }");
        write(dir, "uploads.yml", "dir: /opt/uploads\napiKeys:\n  ANOTHERSECRET: jenkins\n");
        write(dir, "dc-docker.json", "{ \"apiKeys\": { \"DOCKERSECRET\": \"ci\" } }");

        List<CommandInfoItem> commands = new CommandListService(dir.toFile(), Gsons.PRETTY_GSON).listCommands();

        Map<String, CommandInfoItem> byName = commands.stream()
                .collect(toMap(CommandInfoItem::getName, command -> command));

        // docker is excluded, the two others remain
        assertEquals(2, commands.size());
        assertFalse(byName.containsKey("dc-docker"));

        CommandInfoItem web = byName.get("web");
        assertNotNull(web);
        assertEquals("web.jar", web.getParameters().get("jarFilename"));
        assertEquals("web", web.getParameters().get("serviceName"));
        // apiKeys reduced to owner labels — the secret key never appears
        assertEquals("gitlab-ci", web.getParameters().get("apiKeys"));
        assertFalse(web.getParameters().toString().contains("SUPERSECRET"));

        CommandInfoItem uploads = byName.get("uploads");
        assertNotNull(uploads);
        assertEquals("/opt/uploads", uploads.getParameters().get("dir"));
        assertEquals("jenkins", uploads.getParameters().get("apiKeys"));
        assertFalse(uploads.getParameters().toString().contains("ANOTHERSECRET"));
    }

    @Test
    public void zip_archive_version_without_type_is_detected_and_reload_headers_are_masked() throws Exception {
        Path dir = Files.createTempDirectory("command-config");
        String yaml = "apiKeys:\n  KEYSECRET: ci\ndir: /opt/app/bundles\nversionFile: /opt/app/bundles/current\n"
                + "reloadUrl: http://127.0.0.1:8080/reload?version=${version}\n"
                + "reloadHeaders:\n  Authorization: Bearer SERVICESECRET\n  X-Trace: on\n";
        write(dir, "bundle.yml", yaml);

        CommandInfoItem item = new CommandListService(dir.toFile(), Gsons.PRETTY_GSON).listCommands().get(0);
        CommandDetail   detail = new CommandWriteService(dir.toFile(), Gsons.PRETTY_GSON).getCommand("bundle");

        // list and get detect the type independently — both must see the new command, not ZIP_ARCHIVE
        assertEquals(TaskType.ZIP_ARCHIVE_VERSION, item.getType());
        assertEquals(TaskType.ZIP_ARCHIVE_VERSION, detail.getType());
        assertEquals("Authorization, X-Trace", item.getParameters().get("reloadHeaders"));
        assertFalse(item.getParameters().toString().contains("SERVICESECRET"));
        assertFalse(item.getParameters().toString().contains("KEYSECRET"));
    }

    private static void write(Path aDir, String aName, String aContent) throws Exception {
        Files.write(aDir.resolve(aName), aContent.getBytes(UTF_8));
    }
}
