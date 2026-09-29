package com.payneteasy.dcagent.core.modules.docker.filesystem;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/** Files the agent writes with a fixed mode, never through a symbolic link at their place. */
final class ModeFiles {

    private ModeFiles() {
    }

    /** The same refusal for CHECK and PUSH, before the content is read (a link would be followed). */
    static void refuseLink(File aFile) {
        if (Files.isSymbolicLink(aFile.toPath())) {
            throw new IllegalStateException(aFile.getAbsolutePath() + " is a symbolic link; the agent writes this file itself — remove the link");
        }
    }

    static boolean hasMode(File aFile, String aMode) {
        try {
            return Files.getPosixFilePermissions(aFile.toPath(), LinkOption.NOFOLLOW_LINKS).equals(permissions(aMode));
        } catch (IOException e) {
            return false;
        }
    }

    static void writeNoFollow(File aFile, byte[] aBody) {
        try (OutputStream out = Files.newOutputStream(aFile.toPath()
                , StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
                , LinkOption.NOFOLLOW_LINKS)) {
            out.write(aBody);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + aFile.getAbsolutePath() + " (a symbolic link there is refused): " + e, e);
        }
    }

    static void setMode(File aFile, String aMode) {
        try {
            Files.getFileAttributeView(aFile.toPath(), java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(permissions(aMode));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot set mode " + aMode + " on " + aFile.getAbsolutePath(), e);
        }
    }

    /** {@code "0644"} → rw-r--r-- */
    static Set<PosixFilePermission> permissions(String aMode) {
        if (aMode == null || !aMode.matches("0?[0-7]{3}")) {
            throw new IllegalArgumentException("A mode must be octal like 0644, got '" + aMode + "'");
        }
        int mode = Integer.parseInt(aMode, 8);
        Set<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] bits = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE,  PosixFilePermission.GROUP_WRITE,  PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE,  PosixFilePermission.OWNER_WRITE,  PosixFilePermission.OWNER_READ
        };
        for (int i = 0; i < bits.length; i++) {
            if ((mode & (1 << i)) != 0) {
                result.add(bits[i]);
            }
        }
        return result;
    }

    static String describe(String aMode) {
        return PosixFilePermissions.toString(permissions(aMode));
    }
}
