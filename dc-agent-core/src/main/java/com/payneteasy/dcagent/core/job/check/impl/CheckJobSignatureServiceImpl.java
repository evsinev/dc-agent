package com.payneteasy.dcagent.core.job.check.impl;

import com.payneteasy.dcagent.core.exception.HttpProblemException;
import com.payneteasy.dcagent.core.job.check.CheckJobSignatureParam;
import com.payneteasy.dcagent.core.job.check.ICheckJobSignatureService;
import com.payneteasy.dcagent.core.job.create.model.TJobDefinition;
import com.payneteasy.dcagent.core.job.create.model.TJobSignatureValue;

import java.util.Arrays;
import java.util.Base64;

import static com.payneteasy.dcagent.core.exception.DcProblem.*;
import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;
import static com.payneteasy.dcagent.core.util.Hashes.sha256;
import static com.payneteasy.dcagent.core.util.RsaSigner.verifySignature;
import static com.payneteasy.dcagent.core.util.WithTries.withProblem;
import static com.payneteasy.dcagent.core.util.gson.Gsons.PRETTY_GSON;
import static java.nio.charset.StandardCharsets.UTF_8;

public class CheckJobSignatureServiceImpl implements ICheckJobSignatureService {

    public static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Override
    public void checkJobSignature(CheckJobSignatureParam aParam) throws HttpProblemException {
        TJobDefinition     job           = withProblem(() -> json(aParam.getJobJsonFileBytes(), TJobDefinition.class), CANNOT_PARSE_JOB_JSON);
        TJobSignatureValue jobSignature  = withProblem(() -> json(aParam.getJobSignatureJsonFileBytes(), TJobSignatureValue.class), CANNOT_PARSE_JOB_SIGNATURE_JSON);
        byte[]             taskZipSha256 = sha256(aParam.getTaskZipFileBytes());
        byte[]             jobSha256     = sha256(aParam.getJobJsonFileBytes());

        if (!Arrays.equals(job.getSignatureParam().getTaskFileHash(), taskZipSha256)) {
            throw problem(HASH_TASK_ZIP_MISMATCHED)
                    .env("job.getSignatureParam().getTaskFileHash()", BASE64_ENCODER.encodeToString(job.getSignatureParam().getTaskFileHash()))
                    .env("jobSha256", BASE64_ENCODER.encodeToString(jobSha256))
                    .exception();
        }

        if (!verifySignature(aParam.getConsumerCertificate().getPublicKey(), jobSignature.getSignature(), jobSha256)) {
            throw problem(SIGNATURE_JOB_FILE_MISMATCHED)
                    .env("jobSignature.getSignature()", BASE64_ENCODER.encodeToString(jobSignature.getSignature()))
                    .exception();
        }
    }

    private <T> T json(byte[] aBytes, Class<T> aClass) {
        return PRETTY_GSON.fromJson(new String(aBytes, UTF_8), aClass);
    }
}
