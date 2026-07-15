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

    private final File configDir;

    public ConfigBackupService(File configDir) {
        this.configDir = configDir;
    }

    public List<ConfigFileEntry> listConfigFiles() {
        return SafeFiles.listFiles(configDir, File::isFile).stream()
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
