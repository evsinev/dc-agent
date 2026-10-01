package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;

/** 400: the uploaded archive breaks a rule of {@link VersionArchive}; nothing has been written. */
public class ArchiveRefusedException extends ProblemException {

    public ArchiveRefusedException(String aReason) {
        super(400, "archive refused: " + aReason);
    }
}
