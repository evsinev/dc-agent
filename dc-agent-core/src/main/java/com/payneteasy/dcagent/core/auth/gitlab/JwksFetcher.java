package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * {@code dc-agent fetch-jwks}: the issuer file from the issuer's OpenID discovery — never from the
 * token. {@code <issuer>/.well-known/openid-configuration} must name exactly the pinned issuer; its
 * {@code jwks_uri} is fetched; both over HTTPS (plain HTTP only to this host), no redirects, at most {@link IssuerFiles#MAX_BYTES};
 * the keys are checked as the agent reads them. The result is the file content; the caller writes it.
 */
public final class JwksFetcher {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private JwksFetcher() {
    }

    /** A client that follows no redirect. */
    public static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static JsonObject fetch(String aName, String aIssuer, HttpClient aClient, Instant aNow)
            throws IOException, InterruptedException, JwtRejectedException {
        requireScheme(aIssuer, "issuer");
        String base = aIssuer.endsWith("/") ? aIssuer.substring(0, aIssuer.length() - 1) : aIssuer;
        JsonObject discovery = get(aClient, URI.create(base + "/.well-known/openid-configuration"), "discovery");
        if (!aIssuer.equals(IssuerFiles.string(discovery, "issuer"))) {
            throw new JwtRejectedException("the discovery document names another issuer");
        }
        String jwksUri = IssuerFiles.string(discovery, "jwks_uri");
        if (jwksUri == null) {
            throw new JwtRejectedException("the discovery document has no jwks_uri");
        }
        requireScheme(jwksUri, "jwks_uri");
        JsonObject jwks = get(aClient, URI.create(jwksUri), "JWKS");
        JsonElement keys = jwks.get("keys");
        if (keys == null || !keys.isJsonArray()) {
            throw new JwtRejectedException("the JWKS has no keys list");
        }
        IssuerFiles.keys(aName, keys.getAsJsonArray());

        JsonObject file = new JsonObject();
        file.addProperty("issuer", aIssuer);
        file.addProperty("fetchedAt", aNow.toString());
        file.add("jwks", jwks);
        IssuerFiles.parse(aName, file);
        return file;
    }

    private static void requireScheme(String aUrl, String aWhat) throws JwtRejectedException {
        if (!IssuerFiles.isAcceptedUrl(aUrl)) {
            throw new JwtRejectedException(aWhat + " must be an https URL");
        }
    }

    private static JsonObject get(HttpClient aClient, URI aUri, String aWhat) throws IOException, InterruptedException, JwtRejectedException {
        HttpRequest request = HttpRequest.newBuilder(aUri).timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> sent =
                aClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        // the request timeout ends at the headers: the whole exchange, body included, has its own limit
        java.util.concurrent.CompletableFuture<byte[]> read = sent.thenApply(response -> {
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new java.io.UncheckedIOException(new IOException(aWhat + " " + aUri + " answered " + response.statusCode()));
                }
                return body.readNBytes((int) IssuerFiles.MAX_BYTES + 1);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        byte[] bytes;
        try {
            bytes = read.get(TIMEOUT.toSeconds() * 2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            read.cancel(true);
            sent.cancel(true);
            throw new JwtRejectedException(aWhat + " " + aUri + " did not answer within " + TIMEOUT.multipliedBy(2).toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() instanceof java.io.UncheckedIOException unchecked ? unchecked.getCause() : e.getCause();
            throw new JwtRejectedException(aWhat + ": " + (cause == null ? "failed" : cause.getMessage()));
        }
        if (bytes.length > IssuerFiles.MAX_BYTES) {
            throw new JwtRejectedException(aWhat + " is larger than " + IssuerFiles.MAX_BYTES + " bytes");
        }
        return StrictJson.object(bytes, aWhat);
    }
}
