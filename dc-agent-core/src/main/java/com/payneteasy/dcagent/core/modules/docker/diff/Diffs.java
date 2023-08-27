package com.payneteasy.dcagent.core.modules.docker.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Diffs {

    private static final Logger LOG = LoggerFactory.getLogger(Diffs.class);

    public static void logDiff(IActionLogger logger, byte[] aFromBuffer, File aToFile) {
        if (!aToFile.exists()) {
            return;
        }

        try {
            Path fromPath = Files.createTempFile("temp-", ".tmp");
            try {
                Files.write(fromPath, aFromBuffer);
                logDiff(logger, fromPath.toFile(), aToFile);
            } finally {
                Files.delete(fromPath);
            }
        } catch (Exception e) {
            logger.info("  ⚠ Cannot write temp file");
            LOG.error("Cannot write temp file", e);
        }
    }

    public static void logDiff(IActionLogger logger, File aFrom, File aTo) {
        if (!aFrom.exists() || !aTo.exists()) {
            return;
        }

        try {
            List<String> original = Files.readAllLines(aTo.toPath(), UTF_8);
            List<String> modified = Files.readAllLines(aFrom.toPath(), UTF_8);

            Patch<String> diff = DiffUtils.diff(original, modified);

            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(aFrom.getName(), "modified.txt", original, diff, 0);

            for (String line : unifiedDiff) {
                logger.info("  {}", line);
            }
        } catch (Exception e) {
            logger.info("  ⚠ Error while diff {}", e.getMessage());
            LOG.error("Cannot diff from {} to {}", aFrom.getAbsolutePath(), aTo.getAbsolutePath(), e);
        }
    }


}
