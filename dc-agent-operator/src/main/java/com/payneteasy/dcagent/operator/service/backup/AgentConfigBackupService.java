package com.payneteasy.dcagent.operator.service.backup;

import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ConfigBackupRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ConfigFileEntry;
import com.payneteasy.dcagent.core.util.Hashes;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.util.zip.ZipFileBuilder;
import com.payneteasy.dcagent.core.util.zip.ZipFileViewer;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Periodically pulls every agent's config files (via the control plane) and archives them locally:
 * one subdirectory per agent, each backup a {@code <agent>-yyyy-MM-dd-HH-mm-ss.zip}. Skips writing
 * when the configs are unchanged since the newest archive, keeps the last {@code keepCount} archives,
 * and logs each run to {@code .last.txt}.
 */
public class AgentConfigBackupService {

    private static final Logger              LOG           = LoggerFactory.getLogger(AgentConfigBackupService.class);
    private static final ConfigBackupRequest EMPTY         = ConfigBackupRequest.builder().build();
    private static final DateTimeFormatter   ARCHIVE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter   LOG_STAMP     = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IOperatorConfigService configService;
    private final File                   backupDir;
    private final int                    keepCount;

    // Held for the service's (app) lifetime; runs on a daemon thread, so it needs no explicit shutdown.
    private ScheduledExecutorService executor;

    public AgentConfigBackupService(IOperatorConfigService configService, File backupDir, int keepCount) {
        this.configService = configService;
        this.backupDir     = backupDir;
        this.keepCount     = keepCount;
    }

    /** Run immediately on a daemon thread, then every {@code interval}. */
    public void start(Duration interval) {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "agent-config-backup");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(threads);
        LOG.info("Agent config backup: dir={}, keep={}, interval={}", backupDir.getAbsolutePath(), keepCount, interval);
        executor.scheduleWithFixedDelay(this::safeBackupAll, 0, Math.max(1, interval.toMillis()), MILLISECONDS);
    }

    private void safeBackupAll() {
        try {
            backupAll();
        } catch (Exception e) {
            LOG.error("Agent config backup pass failed", e);
        }
    }

    public void backupAll() {
        List<TAgentHost> agents = configService.readConfig().getAgents();
        if (agents == null) {
            return;
        }
        for (TAgentHost agent : agents) {
            try {
                List<ConfigFileEntry> files = configService.agentClient(agent.getName()).backupConfigs(EMPTY).getFiles();
                backupAgent(agent.getName(), files == null ? List.of() : files, LocalDateTime.now(ZoneId.systemDefault()));
            } catch (Exception e) {
                LOG.warn("Cannot back up configs from agent {}", agent.getName(), e);
            }
        }
    }

    // Package-visible for tests: back up one agent's fetched files at the given timestamp.
    void backupAgent(String aAgentName, List<ConfigFileEntry> aFiles, LocalDateTime aTimestamp) {
        File agentDir = new File(backupDir, aAgentName);
        SafeFiles.createDirs(agentDir);

        String freshHash = manifestHash(toContentMap(aFiles));
        File   newest    = newestZip(agentDir);

        if (newest != null && freshHash.equals(hashOfZip(newest))) {
            appendLastLog(agentDir, aTimestamp, "unchanged since " + newest.getName() + ", skipped");
            LOG.debug("Agent {} configs unchanged — skipped", aAgentName);
            return;
        }

        File zip = new File(agentDir, aAgentName + "-" + aTimestamp.format(ARCHIVE_STAMP) + ".zip");
        ZipFileBuilder builder = ZipFileBuilder.buildZipFile();
        for (ConfigFileEntry file : aFiles) {
            builder.add(file.getName(), file.getContent() == null ? "" : file.getContent());
        }
        builder.build(zip);
        appendLastLog(agentDir, aTimestamp, "saved " + zip.getName() + " (" + aFiles.size() + " files)");
        LOG.info("Backed up {} config files for agent {} to {}", aFiles.size(), aAgentName, zip.getName());

        prune(agentDir);
    }

    private void prune(File aAgentDir) {
        List<File> zips = listZips(aAgentDir); // ascending by name => oldest first
        for (int i = 0; i < zips.size() - keepCount; i++) {
            SafeFiles.deleteFileWithWarning(zips.get(i), "old config backup");
        }
    }

    private static List<File> listZips(File aAgentDir) {
        List<File> zips = new ArrayList<>(
                SafeFiles.listFiles(aAgentDir, file -> file.isFile() && file.getName().endsWith(".zip")));
        zips.sort(Comparator.comparing(File::getName));
        return zips;
    }

    private static File newestZip(File aAgentDir) {
        List<File> zips = listZips(aAgentDir);
        return zips.isEmpty() ? null : zips.get(zips.size() - 1);
    }

    private static Map<String, byte[]> toContentMap(List<ConfigFileEntry> aFiles) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (ConfigFileEntry file : aFiles) {
            map.put(file.getName(), (file.getContent() == null ? "" : file.getContent()).getBytes(UTF_8));
        }
        return map;
    }

    private static String hashOfZip(File aZip) {
        try {
            ZipFileViewer viewer = new ZipFileViewer(aZip);
            Map<String, byte[]> map = new LinkedHashMap<>();
            for (String name : viewer.names()) {
                map.put(name, viewer.getItemBytes(name));
            }
            return manifestHash(map);
        } catch (Exception e) {
            LOG.warn("Cannot read prior backup {} — treating as changed", aZip.getAbsolutePath(), e);
            return "";
        }
    }

    /** Stable hash over the file set: entries sorted by name, each as name \0 length \0 bytes. */
    private static String manifestHash(Map<String, byte[]> aFiles) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (String name : new TreeSet<>(aFiles.keySet())) {
            byte[] content = aFiles.get(name);
            buffer.writeBytes(name.getBytes(UTF_8));
            buffer.write(0);
            buffer.writeBytes(Integer.toString(content.length).getBytes(UTF_8));
            buffer.write(0);
            buffer.writeBytes(content);
        }
        return toHex(Hashes.sha256(buffer.toByteArray()));
    }

    private static String toHex(byte[] aBytes) {
        StringBuilder sb = new StringBuilder(aBytes.length * 2);
        for (byte b : aBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void appendLastLog(File aAgentDir, LocalDateTime aTimestamp, String aMessage) {
        File last = new File(aAgentDir, ".last.txt");
        String line = aTimestamp.format(LOG_STAMP) + " " + aMessage + System.lineSeparator();
        try {
            Files.write(last.toPath(), line.getBytes(UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + last.getAbsolutePath(), e);
        }
    }
}
