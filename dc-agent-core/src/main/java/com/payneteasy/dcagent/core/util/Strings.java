package com.payneteasy.dcagent.core.util;

public class Strings {

    public static boolean hasText(String aText) {
        return !isEmpty(aText);
    }

    public static boolean isEmpty(String aText) {
        return aText == null || aText.isEmpty() || aText.trim().isEmpty();
    }

    /**
     * Sanitize an untrusted value for logging: neutralize CR/LF + other control chars (log forging)
     * and {@code < >} markup (in case a log viewer renders the entry as HTML).
     */
    public static String forLog(String aValue) {
        return aValue == null ? "null" : aValue.replaceAll("[\\r\\n\\t<>\\p{Cntrl}]", "_");
    }
}
