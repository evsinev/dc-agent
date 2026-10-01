package com.payneteasy.dcagent.core.util;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readable config values. A bad value throws {@link IllegalArgumentException} whose message
 * describes the expected form and never quotes the value (the caller names the field).
 * <ul>
 *     <li>size — a number with an optional {@code k}, {@code m}, {@code g}, each with an optional
 *     {@code b}, case-insensitive, powers of 1024; a plain number is bytes: {@code 50mb}, {@code 512k}, {@code 10}</li>
 *     <li>count — a number with an optional {@code k} (×1000): {@code 10k}, {@code 500}</li>
 *     <li>duration — {@code 30s}, {@code 5m}, {@code 1h30m} ({@link Duration#parse} with {@code PT} prepended)</li>
 * </ul>
 */
public final class Units {

    private static final Pattern SIZE  = Pattern.compile("^([0-9]{1,18})(?:([kmg])b?)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("^([0-9]{1,18})(k)?$", Pattern.CASE_INSENSITIVE);

    private Units() {
    }

    public static long parseSize(String aValue) {
        Matcher matcher = SIZE.matcher(trimmed(aValue));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected a size like 50mb, 512k or 10 (bytes)");
        }
        long   number = Long.parseLong(matcher.group(1));
        String suffix = matcher.group(2);
        int    shift  = suffix == null ? 0 : switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 10;
            case "m" -> 20;
            default  -> 30;
        };
        if (number > (Long.MAX_VALUE >> shift)) {
            throw new IllegalArgumentException("size is too large");
        }
        return number << shift;
    }

    public static long parseCount(String aValue) {
        Matcher matcher = COUNT.matcher(trimmed(aValue));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected a count like 10k or 500");
        }
        long number = Long.parseLong(matcher.group(1));
        if (matcher.group(2) == null) {
            return number;
        }
        if (number > Long.MAX_VALUE / 1000) {
            throw new IllegalArgumentException("count is too large");
        }
        return number * 1000;
    }

    /** {@code 30s}, {@code 5m}: upper-cased, {@code PT} prepended unless present. A fraction is allowed in seconds only. */
    public static Duration parseDuration(String aValue) {
        String text = trimmed(aValue).toUpperCase(Locale.ROOT);
        if (!text.startsWith("PT")) {
            text = "PT" + text;
        }
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expected a duration like 30s or 5m");
        }
    }

    /** {@link #parseDuration} of {@code aValue}, or of {@code aDefault} when the value has no text. */
    public static Duration parseDuration(String aValue, String aDefault) {
        return parseDuration(Strings.hasText(aValue) ? aValue : aDefault);
    }

    private static String trimmed(String aValue) {
        if (aValue == null) {
            throw new IllegalArgumentException("value is missing");
        }
        return aValue.trim();
    }
}
