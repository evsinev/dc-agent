package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code dc-agent fetch-jwks}: the issuer file from the issuer's OpenID discovery — never from the
 * token. {@code <issuer>/.well-known/openid-configuration} must name exactly the pinned issuer; its
 * {@code jwks_uri} must be on the issuer's origin (scheme, host and port) and is fetched; both over
 * HTTPS (plain HTTP only to this host), no redirects, at most {@link IssuerFiles#MAX_BYTES}. Keys the
 * agent cannot use are left out and named ({@link #usable}); private key material refuses the set;
 * what remains is checked as the agent reads it and must not be empty. The result is the file
 * content; the caller writes it.
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
        return fetch(aName, aIssuer, aClient, aNow, new ArrayList<>());
    }

    /** As {@link #fetch(String, String, HttpClient, Instant)}; {@code aSkipped} receives what was left out of the JWKS. */
    public static JsonObject fetch(String aName, String aIssuer, HttpClient aClient, Instant aNow, List<String> aSkipped)
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
        if (!sameOrigin(aIssuer, jwksUri)) {
            throw new JwtRejectedException("the discovery document names a jwks_uri outside the issuer's origin");
        }
        JsonObject served = get(aClient, URI.create(jwksUri), "JWKS");
        JsonElement keys = served.get("keys");
        if (keys == null || !keys.isJsonArray()) {
            throw new JwtRejectedException("the JWKS has no keys list");
        }
        JsonObject jwks = new JsonObject();
        jwks.add("keys", usable(keys.getAsJsonArray(), aSkipped));
        IssuerFiles.keys(aName, jwks.getAsJsonArray("keys"));

        JsonObject file = new JsonObject();
        file.addProperty("issuer", aIssuer);
        file.addProperty("fetchedAt", aNow.toString());
        file.add("jwks", jwks);
        IssuerFiles.parse(aName, file);
        return file;
    }

    /** Private key parameters (RFC 7518 §6.3.2, §6.4): a JWKS that publishes one is broken — refused whole. */
    private static final Set<String> PRIVATE_FIELDS   = Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");
    /** Certificate references the agent never reads nor follows: left out of the file, the key kept. */
    private static final Set<String> DROPPABLE_FIELDS = Set.of("x5c", "x5t", "x5t#S256", "x5u");

    /**
     * The keys the agent can use, as the agent reads them; what is left out is named in {@code aSkipped}:
     * <ul>
     *     <li>private key material anywhere — the whole JWKS is refused;</li>
     *     <li>not RSA, not RS256, not {@code use: sig}, or {@code key_ops} without {@code verify} — the key is left out;</li>
     *     <li>any other field the agent does not know — the key is left out (a restriction it does not
     *     understand is never dropped);</li>
     *     <li>{@code key_ops} with {@code verify} and the certificate references {@link #DROPPABLE_FIELDS} —
     *     removed, the key kept.</li>
     * </ul>
     * What remains is checked strictly by {@link IssuerFiles#keys}.
     */
    static JsonArray usable(JsonArray aKeys, List<String> aSkipped) throws JwtRejectedException {
        JsonArray usable = new JsonArray();
        for (JsonElement element : aKeys) {
            if (!element.isJsonObject()) {
                usable.add(element); // refused by IssuerFiles.keys
                continue;
            }
            JsonObject jwk = element.getAsJsonObject();
            String     kid = IssuerFiles.string(jwk, "kid");
            for (String field : jwk.keySet()) {
                if (PRIVATE_FIELDS.contains(field)) {
                    throw new JwtRejectedException("the JWKS publishes private key material (key " + shown(kid) + ")");
                }
            }
            if (!"RSA".equals(IssuerFiles.string(jwk, "kty")) || !"RS256".equals(IssuerFiles.string(jwk, "alg"))
                    || !"sig".equals(IssuerFiles.string(jwk, "use"))) {
                aSkipped.add("key " + shown(kid) + ": not an RS256 signing key (kty " + shown(IssuerFiles.string(jwk, "kty"))
                        + ", alg " + shown(IssuerFiles.string(jwk, "alg")) + ", use " + shown(IssuerFiles.string(jwk, "use")) + ")");
                continue;
            }
            if (jwk.has("key_ops") && !verifies(jwk.get("key_ops"))) {
                aSkipped.add("key " + shown(kid) + ": key_ops does not allow verify");
                continue;
            }
            List<String> unknown = new ArrayList<>();
            for (String field : jwk.keySet()) {
                if (!IssuerFiles.JWK_FIELDS.contains(field) && !DROPPABLE_FIELDS.contains(field) && !"key_ops".equals(field)) {
                    unknown.add(field);
                }
            }
            if (!unknown.isEmpty()) {
                aSkipped.add("key " + shown(kid) + ": unknown fields " + unknown.stream().map(JwksFetcher::shown).toList());
                continue;
            }
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> field : jwk.entrySet()) {
                if (IssuerFiles.JWK_FIELDS.contains(field.getKey())) {
                    kept.add(field.getKey(), field.getValue());
                }
            }
            usable.add(kept);
        }
        return usable;
    }

    /** {@code key_ops} is a list of strings that names {@code verify}. */
    private static boolean verifies(JsonElement aOps) {
        if (!aOps.isJsonArray()) {
            return false;
        }
        boolean verify = false;
        for (JsonElement op : aOps.getAsJsonArray()) {
            if (!op.isJsonPrimitive() || !op.getAsJsonPrimitive().isString()) {
                return false;
            }
            verify |= "verify".equals(op.getAsString());
        }
        return verify;
    }

    private static String shown(String aText) {
        if (aText == null) {
            return "-";
        }
        String text = aText.length() > 64 ? aText.substring(0, 64) + "…" : aText;
        return text.replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** The same scheme, host (any letter case) and port, the default port written or not. */
    static boolean sameOrigin(String aIssuer, String aUrl) {
        try {
            URI issuer = URI.create(aIssuer);
            URI url    = URI.create(aUrl);
            return issuer.getScheme() != null && issuer.getScheme().equalsIgnoreCase(url.getScheme())
                    && issuer.getHost() != null && issuer.getHost().equalsIgnoreCase(url.getHost())
                    && url.getRawUserInfo() == null
                    && port(issuer) == port(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(URI aUri) {
        if (aUri.getPort() != -1) {
            return aUri.getPort();
        }
        return "https".equalsIgnoreCase(aUri.getScheme()) ? 443 : 80;
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
