package com.payneteasy.dcagent.core.util;

/** Thrown when an outbound URL is rejected by {@link SsrfGuard} (SSRF protection). */
public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String aMessage) {
        super(aMessage);
    }
}
