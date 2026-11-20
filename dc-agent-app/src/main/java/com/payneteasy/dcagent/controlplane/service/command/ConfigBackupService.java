package com.payneteasy.dcagent.controlplane.service.command;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ConfigFileEntry;
import com.payneteasy.dcagent.core.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
/**
 * Reads every top-level file in CONFIG_DIR as UTF-8 text for the operator's periodic backup. Unlike
 * {@link CommandListService}, this returns raw contents (secrets included) — it's served over the
 * bearer-token-protected control plane.
 */
public class ConfigBackupService {

    /** The same name as {@code DcAgentApplication.CONFIG_LOCK}. */
    public static final String LOCK_FILE = ".dc-agent.lock";

    private final File configDir;

    public ConfigBackupService(File configDir) {
        this.configDir = configDir;
    }

    public List<ConfigFileEntry> listConfigFiles() {
        // Not the agent's lock: closing a descriptor of it would drop the process's POSIX lock, and a
        // second agent could then start on the same CONFIG_DIR.
        return SafeFiles.listFiles(configDir, file -> file.isFile() && !LOCK_FILE.equals(file.getName())).stream()
                .sorted(Comparator.comparing(File::getName))
                .map(ConfigBackupService::toEntry)
                .toList();
    }

    private static ConfigFileEntry toEntry(File aFile) {
        try {
            return ConfigFileEntry.builder()
                    .name(aFile.getName())
                    .content(new String(Files.readAllBytes(aFile.toPath()), UTF_8))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config file " + aFile.getAbsolutePath(), e);
        }
    }
}
