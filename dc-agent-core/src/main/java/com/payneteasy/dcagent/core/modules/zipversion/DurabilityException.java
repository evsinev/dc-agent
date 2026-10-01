package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;

/**
 * 500: an {@code fsync} failed. Whatever was moved is in its intended place; whether it survives a
 * crash is not known. A retry of the same call is safe and syncs again.
 */
public class DurabilityException extends ProblemException {

    public DurabilityException(String aWhat, Throwable aCause) {
        super(500, "durability not confirmed: " + aWhat, aCause);
    }
}
