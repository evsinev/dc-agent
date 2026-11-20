package com.payneteasy.dcagent.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Writes a file by publishing a new one: a temporary sibling {@code .dc-agent-tmp-<random>} is
 * written and renamed over the target. The rename replaces the directory entry — a symbolic link
 * (dangling or not) or a hard link at the target's place is replaced, never written through, so a
 * file linked from elsewhere (a service's {@code run}, a cron file) stays as it was. Writing in place
 * ({@code TRUNCATE_EXISTING}) would change the shared inode even with {@code NOFOLLOW_LINKS}.
 * <p>
 * The new file gets the mode the umask gives, not the mode of the file it replaces. No fsync:
 * callers that need durability use their own writer.
 */
public final class ReplaceFile {

    private ReplaceFile() {
    }

    public static void write(Path aTarget, InputStream aIn) throws IOException {
        Path dir  = aTarget.getParent();
        // short and independent of the target's name: a long (but valid) name must stay writable
        Path temp = dir.resolve(".dc-agent-tmp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        boolean moved = false;
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                aIn.transferTo(out);
            }
            Files.move(temp, aTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
