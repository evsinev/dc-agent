package com.payneteasy.dcagent.core.util;

import java.util.regex.Pattern;

/**
 * One path segment named by a client or a config — a published version, a pointer file: 1–64
 * characters, the first a letter or digit, then letters, digits, {@code . _ -}. So no {@code /},
 * {@code \}, NUL, {@code .}, {@code ..} or leading dot (dot-names belong to the agent), and nothing
 * that needs URL encoding.
 */
public final class SegmentNames {

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private SegmentNames() {
    }

    public static boolean isValid(String aName) {
        return aName != null && SEGMENT.matcher(aName).matches();
    }
}
