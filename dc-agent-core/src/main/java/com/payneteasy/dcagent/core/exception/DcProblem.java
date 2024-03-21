package com.payneteasy.dcagent.core.exception;

public enum DcProblem implements IProblemType {

    UNKNOWN("Unknown", 500),
    CANNOT_PARSE_JOB_JSON("Cannot parse job.json bytes"),
    CANNOT_PARSE_JOB_SIGNATURE_JSON("Cannot parse job-signature.json bytes"),
    NO_SHA256_WITH_RSA("No SHA256withRSA algorithm"),
    INVALID_PUBLIC_KEY("Invalid public key"),
    CANNOT_CALC_SIGNATURE("Cannot calculate signature"),
    SIGNATURE_JOB_FILE_MISMATCHED("Mismatched signature job.json file"),
    HASH_TASK_ZIP_MISMATCHED("Mismatched hash for task.zip and in job.json file"),
    ;

    private final String title;
    private final int    status;

    DcProblem(String title, int status) {
        this.title  = title;
        this.status = status;
    }

    DcProblem(String title) {
        this(title, 500);
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getType() {
        return name();
    }

    @Override
    public int getStatus() {
        return status;
    }
}
