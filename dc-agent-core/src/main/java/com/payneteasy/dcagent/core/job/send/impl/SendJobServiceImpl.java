package com.payneteasy.dcagent.core.job.send.impl;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.job.send.ISendJobService;
import com.payneteasy.dcagent.core.job.send.SendJobParam;
import com.payneteasy.dcagent.core.job.send.SendJobResult;
import com.payneteasy.http.client.api.*;
import com.payneteasy.http.client.api.exceptions.HttpConnectException;
import com.payneteasy.http.client.api.exceptions.HttpReadException;
import com.payneteasy.http.client.api.exceptions.HttpWriteException;
import com.payneteasy.http.client.impl.HttpClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import static com.payneteasy.dcagent.core.util.WithTries.tryCall;
import static com.payneteasy.dcagent.core.util.WithTries.withTry;
import static com.payneteasy.http.client.api.HttpMethod.POST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;

public class SendJobServiceImpl implements ISendJobService {

    private static final Logger LOG = LoggerFactory.getLogger( SendJobServiceImpl.class );

    private static final char[] EMPTY_PASSWORD = "".toCharArray();

    private final IHttpClient httpClient;
    private final Gson        gson;

    public SendJobServiceImpl(Gson aGson) {
        this.httpClient = new HttpClientImpl();
        gson = aGson;
    }

    @Override
    public SendJobResult sendJob(SendJobParam aParam) {
        String url = aParam.getBaseUrl() + "/cli/create-job/" + aParam.getJobId();

        byte[] body;
        try {
            body = readAllBytes(aParam.getJobFile().toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file " + aParam.getJobFile().getAbsolutePath(), e);
        }

        HttpRequest request = HttpRequest.builder()
                .url     ( url  )
                .method  ( POST )
                .body    ( body )
                .headers ( HttpHeaders.singleHeader("Content-Type", "application/zip"))
                .build();

        HttpRequestParameters httpParam = HttpRequestParameters.builder()
                .timeouts(new HttpTimeouts(10_000, 10_000))
                .sslSocketFactory(createSslSocketFactory(aParam.getClientPrivateKey(), aParam.getClientCertificate()))
                .build();

        try {
            LOG.info("Sending to {}", url);
            HttpResponse response     = httpClient.send(request, httpParam);
            String       responseBody = new String(response.getBody(), UTF_8);
            LOG.debug("Response code is {} and body {}", response.getStatusCode(), responseBody);

            if(response.getStatusCode() != 200) {
                throw new IllegalStateException("Bad response code " + response.getStatusCode() + " " + responseBody);
            }

            return gson.fromJson(responseBody, SendJobResult.class);

        } catch (HttpConnectException | HttpReadException | HttpWriteException e) {
            throw new IllegalStateException("Cannot send to " + url, e);
        }
    }

    private SSLSocketFactory createSslSocketFactory(
              @Nonnull PrivateKey      aPrivateKey
            , @Nonnull X509Certificate aClientCertificate
    ) {

        SSLContext sslContext = withTry(() -> SSLContext.getInstance("TLSv1.2"), "Cannot create ssl context for TLSv1.2");
        tryCall(() -> sslContext.init(createKeyManagers(aPrivateKey, aClientCertificate), null, null), "Cannot create init ssl");

        return sslContext.getSocketFactory();
    }

    private KeyManager[] createSimpleKeyManagers(
              @Nonnull PrivateKey      aPrivateKey
            , @Nonnull X509Certificate aClientCertificate
    ) {
          return new KeyManager[]{new SimpleX509KeyManager("client-1", aPrivateKey, aClientCertificate)};
    }


    private KeyManager[] createKeyManagersWithTry(
              @Nonnull PrivateKey      aPrivateKey
            , @Nonnull X509Certificate aClientCertificate
    ) {
        KeyStore store = withTry(() -> KeyStore.getInstance(KeyStore.getDefaultType()), "Cannot create init ssl");
        tryCall(() -> store.load(null, null), "Cannot init store");
        tryCall(() -> store.setKeyEntry("client-key", aPrivateKey, EMPTY_PASSWORD, new Certificate[]{aClientCertificate}), "Cannot add client key and cert to store");

        KeyManagerFactory keyFactory = withTry(() -> KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()), "Cannot get instance of key manager factory for " + KeyManagerFactory.getDefaultAlgorithm());
        tryCall(() -> keyFactory.init(store, EMPTY_PASSWORD), "Cannot init key manager factory");

        return keyFactory.getKeyManagers();
    }

    private KeyManager[] createKeyManagers(
              @Nonnull PrivateKey      aPrivateKey
            , @Nonnull X509Certificate aClientCertificate
    ) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setKeyEntry("client-key", aPrivateKey, EMPTY_PASSWORD, new Certificate[]{aClientCertificate});

            KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyFactory.init(store, EMPTY_PASSWORD);

            return keyFactory.getKeyManagers();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create key factory", e);
        }
    }

}
