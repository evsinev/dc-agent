package com.payneteasy.dcagent.core.job.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.job.ICreateJobService;
import com.payneteasy.dcagent.core.job.messages.CreateJobParam;
import com.payneteasy.dcagent.core.job.model.TJobDefinition;
import com.payneteasy.dcagent.core.job.model.TJobSignatureParam;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.GsonBase64TypeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.core.job.model.JobHashType.SHA256;
import static com.payneteasy.dcagent.core.job.model.JobSignatureMethodType.RSA_SHA256;
import static com.payneteasy.dcagent.core.util.RsaSigner.calculateRsaSignature;
import static com.payneteasy.dcagent.core.util.ZipFileBuilder.buildZipFile;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;

public class CreateJobServiceImpl implements ICreateJobService {

    private static final Logger LOG = LoggerFactory.getLogger( CreateJobServiceImpl.class );

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(byte[].class, new GsonBase64TypeAdapter())
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    @Override
    public TempFile createJob(CreateJobParam aJobParam) {
        TJobSignatureParam signatureParam = TJobSignatureParam.builder()
                .consumerKey        ( aJobParam.getConsumerKey()        )
                .hash               ( calcHash(aJobParam.getTaskFile()) )
                .jobId              ( aJobParam.getJobId()              )
                .taskType           (aJobParam.getTaskType()            )
                .hashType           ( SHA256                            )
                .signatureMethod    ( RSA_SHA256                        )
                .timestampMs        ( currentTimeMillis()               )
                .build();

        String signatureBaseString = toSignatureBaseString(signatureParam);

        TJobDefinition job = TJobDefinition.builder()
                .signatureParam      ( signatureParam      )
                .signatureBaseString ( signatureBaseString )
                .signature           ( calculateRsaSignature(aJobParam.getPrivateKey(), signatureBaseString.getBytes(UTF_8)))
                .taskName            ( aJobParam.getTaskFile().getName() )
                .taskType            ( aJobParam.getTaskType()           )
                .jobId               ( aJobParam.getJobId()              )
                .build();

        String json = GSON.toJson(job);
        LOG.debug("Job is {}", json);

        return buildZipFile()
                .add("task.zip"  , aJobParam.getTaskFile())
                .add("job.json"  , json)
                .add("client.crt", aJobParam.getCertificateFile())
                .build(new TempFile("docker", "zip"));
    }

    private String toSignatureBaseString(TJobSignatureParam aParam) {
        JsonObject object = (JsonObject) GSON.toJsonTree(aParam);

        return object.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().getAsString())
                .collect(Collectors.joining(";"));


    }

    private byte[] calcHash(File aFile) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    readAllBytes(aFile.toPath())
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256 alg", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file " + aFile.getAbsolutePath(), e);
        }
    }

    private String createNonce(int aSize) {
        byte[] buffer = new byte[aSize];
        ThreadLocalRandom.current().nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
