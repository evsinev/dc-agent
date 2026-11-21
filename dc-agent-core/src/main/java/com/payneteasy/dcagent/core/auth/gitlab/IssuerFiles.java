package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code KEYS_DIR/<name>.json} — the issuer a {@code GITLAB_JWT} key trusts:
 * <pre>
 * { "issuer": "https://code.clubber.me/git",            pinned; the token's iss must equal it
 *   "fetchedAt": "2026-11-21T03:00:00Z",                 optional; written by fetch-jwks
 *   "jwks": { "keys": [ { "kty": "RSA", "kid": "…", "n": "…", "e": "AQAB", "alg": "RS256", "use": "sig" } ] } }
 * </pre>
 * Read on every use (a removed key stops working at once), never through a link, strict: unknown
 * fields (in the file, the JWKS or a key), a missing or duplicate {@code kid}, a non-RSA or short
 * (&lt; 2048 bits) key, a missing or other {@code alg} than RS256 or {@code use} than sig — the whole
 * file is refused, and so is every token of this issuer.
 */
public final class IssuerFiles {

    public static final long MAX_BYTES        = 256 * 1024;
    public static final int  MIN_RSA_BITS     = 2048;

    private static final Pattern     NAME       = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> TOP_FIELDS = Set.of("issuer", "fetchedAt", "jwks");
    /** What GitLab puts into a JWK — anything else (a typo such as "ues") refuses the set. */
    private static final Set<String> JWK_FIELDS = Set.of("kty", "kid", "n", "e", "alg", "use");

    private IssuerFiles() {
    }

    public static Path path(Path aKeysDir, String aName) throws JwtRejectedException {
        if (aName == null || !NAME.matcher(aName).matches()) {
            throw new JwtRejectedException("invalid issuer name");
        }
        return aKeysDir.resolve(aName + ".json");
    }

    public static IssuerKeys load(Path aKeysDir, String aName) throws JwtRejectedException {
        Path file = path(aKeysDir, aName);
        byte[] bytes;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new JwtRejectedException("issuer file " + file.getFileName() + " is missing or not a regular file");
            }
            if (Files.size(file) > MAX_BYTES) {
                throw new JwtRejectedException("issuer file " + file.getFileName() + " is larger than " + MAX_BYTES + " bytes");
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new JwtRejectedException("issuer file " + file.getFileName() + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
        return parse(aName, StrictJson.object(bytes, "issuer file " + file.getFileName()));
    }

    public static IssuerKeys parse(String aName, JsonObject aFile) throws JwtRejectedException {
        for (String field : aFile.keySet()) {
            if (!TOP_FIELDS.contains(field)) {
                throw new JwtRejectedException("issuer file " + aName + ": unknown field");
            }
        }
        String issuer = string(aFile, "issuer");
        if (!isAcceptedUrl(issuer)) {
            throw new JwtRejectedException("issuer file " + aName + ": \"issuer\" must be an https URL");
        }
        Instant fetchedAt = null;
        if (aFile.has("fetchedAt")) {
            String text = string(aFile, "fetchedAt");
            try {
                fetchedAt = text == null ? null : Instant.parse(text);
            } catch (DateTimeParseException e) {
                fetchedAt = null;
            }
            if (fetchedAt == null) {
                throw new JwtRejectedException("issuer file " + aName + ": \"fetchedAt\" must be an ISO-8601 instant");
            }
        }
        JsonElement jwks = aFile.get("jwks");
        if (jwks == null || !jwks.isJsonObject() || !jwks.getAsJsonObject().keySet().equals(Set.of("keys"))
                || !jwks.getAsJsonObject().get("keys").isJsonArray()) {
            throw new JwtRejectedException("issuer file " + aName + ": \"jwks\" must be an object with a \"keys\" list");
        }
        return new IssuerKeys(aName, issuer, keys(aName, jwks.getAsJsonObject().getAsJsonArray("keys")), fetchedAt);
    }

    /** The keys of a JWKS; any unusable key refuses the whole set. */
    public static Map<String, RSAPublicKey> keys(String aName, JsonArray aKeys) throws JwtRejectedException {
        if (aKeys.isEmpty()) {
            throw new JwtRejectedException("issuer " + aName + ": the JWKS has no keys");
        }
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (JsonElement element : aKeys) {
            if (!element.isJsonObject()) {
                throw new JwtRejectedException("issuer " + aName + ": a JWKS entry is not an object");
            }
            JsonObject jwk = element.getAsJsonObject();
            for (String field : jwk.keySet()) {
                if (!JWK_FIELDS.contains(field)) {
                    throw new JwtRejectedException("issuer " + aName + ": a JWKS key has an unknown field");
                }
            }
            String kid = string(jwk, "kid");
            if (kid == null || kid.isEmpty()) {
                throw new JwtRejectedException("issuer " + aName + ": a key without kid");
            }
            if (keys.containsKey(kid)) {
                throw new JwtRejectedException("issuer " + aName + ": kid " + kid + " twice");
            }
            if (!"RSA".equals(string(jwk, "kty"))) {
                throw new JwtRejectedException("issuer " + aName + ": key " + kid + " is not RSA");
            }
            if (!"RS256".equals(string(jwk, "alg"))) {
                throw new JwtRejectedException("issuer " + aName + ": key " + kid + " is not for RS256 (alg)");
            }
            if (!"sig".equals(string(jwk, "use"))) {
                throw new JwtRejectedException("issuer " + aName + ": key " + kid + " is not a signing key (use)");
            }
            BigInteger modulus  = unsigned(aName, kid, string(jwk, "n"));
            BigInteger exponent = unsigned(aName, kid, string(jwk, "e"));
            if (modulus.bitLength() < MIN_RSA_BITS) {
                throw new JwtRejectedException("issuer " + aName + ": key " + kid + " is shorter than " + MIN_RSA_BITS + " bits");
            }
            if (exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)) {
                throw new JwtRejectedException("issuer " + aName + ": key " + kid + " has an unusable exponent");
            }
            try {
                keys.put(kid, (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            } catch (java.security.GeneralSecurityException e) {
                throw new JwtRejectedException("issuer " + aName + ": key " + kid + " cannot be built (" + e.getClass().getSimpleName() + ")");
            }
        }
        return Collections.unmodifiableMap(keys);
    }

    private static BigInteger unsigned(String aName, String aKid, String aBase64Url) throws JwtRejectedException {
        byte[] bytes = Base64Url.decode(aBase64Url);
        if (bytes == null || bytes.length == 0) {
            throw new JwtRejectedException("issuer " + aName + ": key " + aKid + " has a malformed n or e");
        }
        return new BigInteger(1, bytes);
    }

    /**
     * HTTPS, or plain HTTP to this host only (127.0.0.1, localhost, [::1]) — a local issuer for tests and
     * the smoke; a token naming it can only come from this host.
     */
    public static boolean isAcceptedUrl(String aUrl) {
        if (aUrl == null) {
            return false;
        }
        if (aUrl.startsWith("https://")) {
            return true;
        }
        return LOOPBACK_HTTP.matcher(aUrl).lookingAt();
    }

    private static final Pattern LOOPBACK_HTTP = Pattern.compile("http://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:[0-9]{1,5})?(/|$)");

    static String string(JsonObject aObject, String aField) {
        JsonElement element = aObject.get(aField);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }

    /** Canonical base64url, no padding (RFC 7515 §2); null when it is not. */
    static final class Base64Url {

        private static final Pattern ALPHABET = Pattern.compile("[A-Za-z0-9_-]*");

        private Base64Url() {
        }

        static byte[] decode(String aText) {
            if (aText == null || !ALPHABET.matcher(aText).matches() || aText.length() % 4 == 1) {
                return null;
            }
            byte[] bytes;
            try {
                bytes = Base64.getUrlDecoder().decode(aText);
            } catch (IllegalArgumentException e) {
                return null;
            }
            // canonical: the unused low bits of the last character are zero (one string per byte sequence)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(aText) ? bytes : null;
        }
    }
}
