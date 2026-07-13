package com.payneteasy.dcagent.operator.service.backup;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ConfigFileEntry;
import com.payneteasy.dcagent.core.util.zip.ZipFileViewer;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentConfigBackupServiceTest {

    private static ConfigFileEntry entry(String aName, String aContent) {
        return ConfigFileEntry.builder().name(aName).content(aContent).build();
    }

    private static LocalDateTime ts(int aSecond) {
        return LocalDateTime.of(2026, 7, 13, 10, 0, aSecond);
    }

    private static List<String> zipNames(File aAgentDir) {
        File[] files = aAgentDir.listFiles((dir, name) -> name.endsWith(".zip"));
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                names.add(file.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static String lastLog(File aAgentDir) throws Exception {
        return new String(Files.readAllBytes(new File(aAgentDir, ".last.txt").toPath()), UTF_8);
    }

    @Test
    public void first_backup_writes_a_named_zip_with_the_files_and_logs_saved() throws Exception {
        Path root = Files.createTempDirectory("backup");
        AgentConfigBackupService svc = new AgentConfigBackupService(null, root.toFile(), 5);

        svc.backupAgent("sandbox-1", asList(entry("billing.json", "{\"type\":\"JAR\"}")), ts(0));

        File agentDir = new File(root.toFile(), "sandbox-1");
        assertEquals(asList("sandbox-1-2026-07-13-10-00-00.zip"), zipNames(agentDir));

        ZipFileViewer zip = new ZipFileViewer(new File(agentDir, "sandbox-1-2026-07-13-10-00-00.zip"));
        assertEquals("{\"type\":\"JAR\"}", zip.getItemText("billing.json"));
        assertTrue(lastLog(agentDir).contains("saved sandbox-1-2026-07-13-10-00-00.zip (1 files)"));
    }

    @Test
    public void unchanged_configs_are_skipped_and_logged() throws Exception {
        Path root = Files.createTempDirectory("backup");
        AgentConfigBackupService svc = new AgentConfigBackupService(null, root.toFile(), 5);
        File agentDir = new File(root.toFile(), "sandbox-1");

        List<ConfigFileEntry> files = asList(entry("a.json", "one"), entry("b.json", "two"));
        svc.backupAgent("sandbox-1", files, ts(0));
        svc.backupAgent("sandbox-1", files, ts(30));

        assertEquals(1, zipNames(agentDir).size()); // no second archive
        assertTrue(lastLog(agentDir).contains("unchanged since sandbox-1-2026-07-13-10-00-00.zip, skipped"));
    }

    @Test
    public void changed_configs_write_a_new_zip() throws Exception {
        Path root = Files.createTempDirectory("backup");
        AgentConfigBackupService svc = new AgentConfigBackupService(null, root.toFile(), 5);
        File agentDir = new File(root.toFile(), "sandbox-1");

        svc.backupAgent("sandbox-1", asList(entry("a.json", "one")), ts(0));
        svc.backupAgent("sandbox-1", asList(entry("a.json", "CHANGED")), ts(1));

        assertEquals(2, zipNames(agentDir).size());
    }

    @Test
    public void rotation_keeps_only_the_newest_archives() throws Exception {
        Path root = Files.createTempDirectory("backup");
        AgentConfigBackupService svc = new AgentConfigBackupService(null, root.toFile(), 3);
        File agentDir = new File(root.toFile(), "sandbox-1");

        for (int i = 0; i < 5; i++) {
            svc.backupAgent("sandbox-1", asList(entry("a.json", "v" + i)), ts(i));
        }

        assertEquals(
                asList(
                        "sandbox-1-2026-07-13-10-00-02.zip",
                        "sandbox-1-2026-07-13-10-00-03.zip",
                        "sandbox-1-2026-07-13-10-00-04.zip"),
                zipNames(agentDir));
    }
}
