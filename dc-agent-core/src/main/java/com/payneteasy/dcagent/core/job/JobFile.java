package com.payneteasy.dcagent.core.job;

import com.payneteasy.dcagent.core.job.create.model.TJobDefinition;
import com.payneteasy.dcagent.core.job.create.model.TJobSignatureValue;
import com.payneteasy.dcagent.core.util.zip.ZipFileViewer;

import java.io.File;

import static com.payneteasy.dcagent.core.exception.DcProblem.CANNOT_PARSE_JOB_JSON;
import static com.payneteasy.dcagent.core.exception.DcProblem.CANNOT_PARSE_JOB_SIGNATURE_JSON;
import static com.payneteasy.dcagent.core.util.Gsons.PRETTY_GSON;
import static com.payneteasy.dcagent.core.util.WithTries.withProblem;

public class JobFile {

    private final TJobDefinition     jobDefinition;
    private final byte[]             taskBytes;
    private final byte[]             jobBytes;
    private final byte[]             jobSignatureBytes;
    private final TJobSignatureValue jobSignature;

    public JobFile(File aFile) {
        ZipFileViewer zipViewer = new ZipFileViewer(aFile);
        taskBytes         = zipViewer.getItemBytes("task.zip");
        jobDefinition     = withProblem(() -> PRETTY_GSON.fromJson(zipViewer.getItemText("job.json"), TJobDefinition.class), CANNOT_PARSE_JOB_JSON);
        jobSignature      = withProblem(() -> PRETTY_GSON.fromJson(zipViewer.getItemText("job-signature.json"), TJobSignatureValue.class), CANNOT_PARSE_JOB_SIGNATURE_JSON);
        jobBytes          = zipViewer.getItemBytes("job.json");
        jobSignatureBytes = zipViewer.getItemBytes("job-signature.json");
    }

    public TJobDefinition getJobDefinition() {
        return jobDefinition;
    }

    public byte[] getTaskBytes() {
        return taskBytes;
    }

    public TJobSignatureValue getJobSignature() {
        return jobSignature;
    }

    public byte[] getJobBytes() {
        return jobBytes;
    }

    public byte[] getJobSignatureBytes() {
        return jobSignatureBytes;
    }
}
