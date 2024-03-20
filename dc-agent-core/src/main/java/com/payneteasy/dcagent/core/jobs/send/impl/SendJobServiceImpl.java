package com.payneteasy.dcagent.core.jobs.send.impl;

import com.payneteasy.dcagent.core.jobs.send.ISendJobService;
import com.payneteasy.dcagent.core.jobs.send.SendJobParam;
import com.payneteasy.http.client.api.*;
import com.payneteasy.http.client.api.exceptions.HttpConnectException;
import com.payneteasy.http.client.api.exceptions.HttpReadException;
import com.payneteasy.http.client.api.exceptions.HttpWriteException;
import com.payneteasy.http.client.impl.HttpClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import static com.payneteasy.dcagent.core.util.WithTries.tryCall;
import static com.payneteasy.dcagent.core.util.WithTries.withTry;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;

public class SendJobServiceImpl implements ISendJobService {

    private static final Logger LOG = LoggerFactory.getLogger( SendJobServiceImpl.class );

    private static final char[] EMPTY_PASSWORD = "".toCharArray();

    private final IHttpClient httpClient;

    public SendJobServiceImpl() {
        this.httpClient = new HttpClientImpl();
    }

    @Override
    public void sendJob(SendJobParam aParam) {
        String url = aParam.getBaseUrl() + "/cli/create-job";

        byte[] body;
        try {
            body = readAllBytes(aParam.getJobFile().toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file " + aParam.getJobFile().getAbsolutePath(), e);
        }

        HttpRequest request = HttpRequest.builder()
                .url    (url)
                .method ( HttpMethod.POST)
                .body   ( body )
                .build();

        HttpRequestParameters httpParam = HttpRequestParameters.builder()
                .timeouts(new HttpTimeouts(10_000, 10_000))
                .sslSocketFactory(createSslSocketFactory(aParam.getClientPrivateKey(), aParam.getClientCertificate()))
                .build();

        try {
            LOG.debug("Sending to {}", url);
            HttpResponse response     = httpClient.send(request, httpParam);
            String       responseBody = new String(response.getBody(), UTF_8);
            LOG.debug("Response code is {} and body {}", response.getStatusCode(), responseBody);

            if(response.getStatusCode() != 200) {
                throw new IllegalStateException("Bad response code " + response.getStatusCode() + " " + responseBody);
            }
            
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


    private KeyManager[] createKeyManagers(
              @Nonnull PrivateKey      aPrivateKey
            , @Nonnull X509Certificate aClientCertificate
    ) {
        KeyStore store = withTry(() -> KeyStore.getInstance(KeyStore.getDefaultType()), "Cannot create init ssl");
        tryCall(() -> store.load(null, null), "Cannot init store");
        tryCall(() -> store.setKeyEntry("client-key", aPrivateKey, EMPTY_PASSWORD, new Certificate[]{aClientCertificate}), "Cannot add client key and cert to store");

        KeyManagerFactory keyFactory = withTry(() -> KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()), "Cannot get instance of key manager factory for " + KeyManagerFactory.getDefaultAlgorithm());
        tryCall(() -> keyFactory.init(store, EMPTY_PASSWORD), "Cannot init key manager factory");

        TrustManagerFactory trustFactory = withTry(() -> TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()), "Cannot create trust manager factory");
        tryCall(() -> trustFactory.init(store), "Cannot init trust factory");

        return keyFactory.getKeyManagers();
    }

}
