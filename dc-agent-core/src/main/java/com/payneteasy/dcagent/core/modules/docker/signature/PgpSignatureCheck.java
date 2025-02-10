package com.payneteasy.dcagent.core.modules.docker.signature;

import com.payneteasy.dcagent.core.config.model.docker.TSignatureConfig;
import com.payneteasy.dcagent.core.modules.docker.IActionLogger;
import org.pgpainless.sop.SOPImpl;
import sop.SOP;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;

public class PgpSignatureCheck {

    private final SOP              sop             = new SOPImpl();
    private final TSignatureConfig signatureConfig = TSignatureConfig.builder().build();
    private final HttpClient       httpClient      = HttpClient.newBuilder().build();

    public void checkPgpSignature(String aArtifactUrl, File aArtifactFile, IActionLogger logger) {
        byte[] signature = fetchSignature(aArtifactUrl + ".asc");
        checkSignature(signature, aArtifactFile);
    }

    private void checkSignature(byte[] signature, File aArtifactFile) {
        try (InputStream in = Files.newInputStream(aArtifactFile.toPath())) {
            sop.detachedVerify()
                    .cert(signatureConfig.getPgpPublicKey().getBytes(UTF_8))
                    .signatures(signature)
                    .data(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file " + aArtifactFile.getAbsolutePath(), e);
        }
    }

    private byte[] fetchSignature(String aUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Error for " + aUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted for " + aUrl, e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Bad response for " + aUrl);
        }

        return response.body();
    }
}
