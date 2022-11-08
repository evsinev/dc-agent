package com.payneteasy.dcagent.core.util;

public class Strings {

    public static boolean hasText(String aText) {
        return !isEmpty(aText);
    }

    public static boolean isEmpty(String aText) {
        return aText == null || aText.isEmpty() || aText.trim().isEmpty();
    }
}
