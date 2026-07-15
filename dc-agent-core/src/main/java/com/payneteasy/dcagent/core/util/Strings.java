package com.payneteasy.dcagent.core.util;

public class Strings {

    public static boolean hasText(String aText) {
        return !isEmpty(aText);
    }

    public static boolean isEmpty(String aText) {
        return aText == null || aText.isEmpty() || aText.trim().isEmpty();
    }

    /** Sanitize an untrusted value for logging: strip CR/LF and other control chars (log-forging guard). */
    public static String forLog(String aValue) {
        return aValue == null ? "null" : aValue.replaceAll("\\p{Cntrl}", "_");
    }
}
