package com.payneteasy.dcagent.core.util.zip;

import java.util.List;

/**
 * A task directory has entries the packer cannot read. Packing fails instead of skipping them: a
 * skipped file would never reach the host (or, with {@code sync: MIRROR}, be deleted there).
 */
public class UnreadableTaskFilesException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public UnreadableTaskFilesException(String aBaseDir, List<String> aProblems) {
        super("Cannot pack " + aBaseDir + ", nothing was sent; make these readable for the operator:\n  - "
                + String.join("\n  - ", aProblems));
    }
}
