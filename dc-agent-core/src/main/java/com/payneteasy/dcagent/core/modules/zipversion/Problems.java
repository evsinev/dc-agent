package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;

/** The non-2xx outcomes of {@code zip-archive-version} that are not a refusal of the archive or the config. */
final class Problems {

    private Problems() {
    }

    static ProblemException conflict(String aReason) {
        return new ProblemException(409, aReason);
    }

    static ProblemException busy(String aReason) {
        return new ProblemException(503, "busy: " + aReason);
    }
}
