package com.payneteasy.dcagent.core.job.create.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.job.create.ICreateJobService;
import com.payneteasy.dcagent.core.job.create.messages.CreateJobParam;
import com.payneteasy.dcagent.core.job.create.model.TJobDefinition;
import com.payneteasy.dcagent.core.job.create.model.TJobSignatureParam;
import com.payneteasy.dcagent.core.job.create.model.TJobSignatureValue;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.Gsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.core.job.create.model.JobHashType.SHA256;
import static com.payneteasy.dcagent.core.job.create.model.JobSignatureMethod.RSA_SHA256;
import static com.payneteasy.dcagent.core.util.Hashes.sha256;
import static com.payneteasy.dcagent.core.util.RsaSigner.calculateRsaSignature;
import static com.payneteasy.dcagent.core.util.zip.ZipFileBuilder.buildZipFile;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;

public class CreateJobServiceImpl implements ICreateJobService {

    private static final Logger LOG = LoggerFactory.getLogger( CreateJobServiceImpl.class );

    private static final Gson GSON = Gsons.PRETTY_GSON;

    @Override
    public TempFile createJob(CreateJobParam aJobParam) {
        TJobSignatureParam signatureParam = TJobSignatureParam.builder()
                .consumerKey        ( aJobParam.getConsumerKey()        )
                .taskFileHash       ( sha256(aJobParam.getTaskFile())   )
                .taskFileHashType   ( SHA256                            )
                .nonce              ( createNonce(64)             )
                .taskType           ( aJobParam.getTaskType()           )
                .taskFileHashType   ( SHA256                            )
                .timestampMs        ( currentTimeMillis()               )
                .build();

        TJobDefinition job = TJobDefinition.builder()
                .signatureParam ( signatureParam          )
                .taskName       ( aJobParam.getTaskName() )
                .taskType       ( aJobParam.getTaskType() )
                .jobId          ( aJobParam.getJobId()    )
                .taskHost       ( aJobParam.getTaskHost() )
                .build();

        String jobJson = GSON.toJson(job);
        LOG.debug("Job definition is {}", jobJson);

        byte[] jobFileHash = sha256(jobJson.getBytes(UTF_8));
        TJobSignatureValue signature = TJobSignatureValue.builder()
                .jobFileHash     ( jobFileHash )
                .jobFileHashType ( SHA256 )
                .signature       ( calculateRsaSignature(aJobParam.getPrivateKey(), jobFileHash) )
                .signatureMethod ( RSA_SHA256 )
                .build();

        String signatureJson = GSON.toJson(signature);
        LOG.debug("Signature is {}", signatureJson);

        return buildZipFile()
                .add("task.zip"           , aJobParam.getTaskFile())
                .add("job.json"           , jobJson)
                .add("job-signature.json" , signatureJson)
                .add("client.crt"         , aJobParam.getCertificateFile())
                .build(new TempFile("docker", "zip"));
    }

    private String toSignatureBaseString(TJobSignatureParam aParam) {
        JsonObject object = (JsonObject) GSON.toJsonTree(aParam);

        return object.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().getAsString())
                .collect(Collectors.joining(";"));

    }

    private String createNonce(int aSize) {
        byte[] buffer = new byte[aSize];
        ThreadLocalRandom.current().nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
