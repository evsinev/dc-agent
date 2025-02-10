package com.acme.dcagent.upload.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;
import static java.net.http.HttpRequest.BodyPublishers.ofFile;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;

public class ArtifactUploader {

    private static final Logger LOG = LoggerFactory.getLogger( ArtifactUploader.class );

    public void uploadArtifact(ArtifactUploaderParams aParams) throws IllegalStateException {
        String url = aParams.getBaseUrl()
                + "/"
                + aParams.getArtifactName()
                + "__"
                + aParams.getArtifactName()
                + "-"
                + aParams.getArtifactVersion()
        ;

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(ofSeconds(30)).build()) {

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout    ( ofSeconds(120)  )
                    .uri        ( URI.create(url) )
                    .POST       ( createBodyPublisher(aParams) )
                    .header     ( "api-key", aParams.getUploadKey())
                    .header     ( "content-type", "application/zip")
                    .header     ( "x-dc-agent-file-extension", aParams.getFileExtension())
                    .build();


            LOG.info("Sending to {}", url);
            HttpResponse<String> response = sendRequest(client, request);
            LOG.info("Response is {}", response.body());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Expected status 200 but was " + response.statusCode());
            }

            LOG.info("Deployed successfully");

        }
    }

    private static HttpResponse<String> sendRequest(HttpClient client, HttpRequest request){
        try {
            return client.send(request, ofString(UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot send to " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending to " + request.uri(), e);
        }
    }

    private HttpRequest.BodyPublisher createBodyPublisher(ArtifactUploaderParams aParams) {
        if (aParams.getBytesToUpload() != null) {
            LOG.info("Sending {} bytes", aParams.getBytesToUpload().length);
            return ofByteArray(aParams.getBytesToUpload());
        }

        File file = aParams.getFileToUpload();
        if (file == null) {
            throw new IllegalStateException("Neither bytesToUpload nor fileToUpload set");
        }

        if (!file.exists()) {
            throw new IllegalStateException("File not exist: " + file.getAbsolutePath());
        }

        LOG.debug("Sending {} bytes of file {}", file.length(), file.getAbsolutePath());

        try {
            return ofFile(file.toPath());
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Cannot read file " + file.getAbsolutePath(), e);
        }
    }
}
