package com.payneteasy.dcagent.core.modules.docker.resolver;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code mode} of a volume directory: three octal digits with an optional leading zero
 * ({@code "770"}, {@code "0770"}). setuid/setgid/sticky bits are not supported.
 */
public final class VolumeMode {

    private static final Pattern MODE = Pattern.compile("0?[0-7]{3}");

    private VolumeMode() {
    }

    public static boolean isValid(String aMode) {
        return aMode != null && MODE.matcher(aMode).matches();
    }

    /** Permission bits of a valid mode, e.g. {@code "0770"} → {@code 0770}. */
    public static int toBits(String aMode) {
        if (!isValid(aMode)) {
            throw new IllegalArgumentException("Invalid mode '" + aMode + "'");
        }
        int bits = 0;
        for (char digit : aMode.toCharArray()) {
            bits = bits * 8 + (digit - '0');
        }
        return bits;
    }

    public static Set<PosixFilePermission> toPermissions(String aMode) {
        if (!isValid(aMode)) {
            throw new IllegalArgumentException("Invalid mode '" + aMode + "'");
        }
        String digits = aMode.length() == 4 ? aMode.substring(1) : aMode;
        StringBuilder sb = new StringBuilder(9);
        for (char digit : digits.toCharArray()) {
            int bits = digit - '0';
            sb.append((bits & 4) != 0 ? 'r' : '-');
            sb.append((bits & 2) != 0 ? 'w' : '-');
            sb.append((bits & 1) != 0 ? 'x' : '-');
        }
        return PosixFilePermissions.fromString(sb.toString());
    }
}
