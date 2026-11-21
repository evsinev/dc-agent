package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Verifies a GitLab CI/CD ID token against one issuer and one audience; returns its claims or says
 * why not. Only what this checks is trusted afterwards:
 * <ol>
 *     <li>at most {@value #MAX_TOKEN_BYTES} bytes; three canonical base64url parts;</li>
 *     <li>header: strict UTF-8 JSON without duplicates; {@code alg} exactly {@code RS256}; {@code kid}
 *     present and in the issuer file; {@code jku}, {@code x5u}, {@code jwk}, {@code x5c},
 *     {@code crit} refused (nothing is ever fetched by a token's say);</li>
 *     <li>the RS256 signature over the original bytes of {@code header.payload};</li>
 *     <li>payload: strict JSON; {@code iss} equals the pinned issuer; {@code aud} is the audience
 *     (a string, or a list of strings that holds it);</li>
 *     <li>time, {@code exp}, {@code nbf}, {@code iat} required integers, a skew of
 *     {@value #SKEW_SECONDS} s: {@code exp > now - skew}, {@code nbf <= now + skew},
 *     {@code iat <= now + skew}, {@code iat < exp <= iat + 24h}, {@code now - iat <= maxTokenAge}.</li>
 * </ol>
 */
public final class GitlabTokenVerifier {

    public static final int  MAX_TOKEN_BYTES = 8 * 1024;
    public static final long SKEW_SECONDS    = 60;
    public static final long MAX_LIFETIME    = Duration.ofHours(24).toSeconds();

    private static final Set<String> FORBIDDEN_HEADERS = Set.of("jku", "x5u", "jwk", "x5c", "crit");
    private static final Pattern     INTEGER           = Pattern.compile("-?[0-9]{1,15}");

    private GitlabTokenVerifier() {
    }

    public static JsonObject verify(String aToken, IssuerKeys aIssuer, String aAudience, Duration aMaxAge, Instant aNow)
            throws JwtRejectedException {
        if (aToken == null || aToken.isEmpty()) {
            throw new JwtRejectedException("no token");
        }
        if (aToken.length() > MAX_TOKEN_BYTES) {
            throw new JwtRejectedException("token longer than " + MAX_TOKEN_BYTES + " bytes");
        }
        String[] parts = aToken.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtRejectedException("not three parts");
        }
        byte[] headerBytes  = decode(parts[0], "header");
        byte[] payloadBytes = decode(parts[1], "payload");
        byte[] signature    = decode(parts[2], "signature");

        JsonObject header = StrictJson.object(headerBytes, "token header");
        if (!"RS256".equals(IssuerFiles.string(header, "alg"))) {
            throw new JwtRejectedException("alg is not RS256");
        }
        for (String name : header.keySet()) {
            if (FORBIDDEN_HEADERS.contains(name)) {
                throw new JwtRejectedException("header " + name + " is not accepted");
            }
        }
        String kid = IssuerFiles.string(header, "kid");
        if (kid == null || kid.isEmpty()) {
            throw new JwtRejectedException("no kid");
        }
        RSAPublicKey key = aIssuer.keys().get(kid);
        if (key == null) {
            throw new JwtRejectedException("unknown kid for issuer " + aIssuer.name() + " (run fetch-jwks?)");
        }
        if (signature.length != (key.getModulus().bitLength() + 7) / 8) {
            throw new JwtRejectedException("bad signature length");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(US_ASCII));
            if (!verifier.verify(signature)) {
                throw new JwtRejectedException("bad signature");
            }
        } catch (GeneralSecurityException e) {
            throw new JwtRejectedException("signature cannot be checked (" + e.getClass().getSimpleName() + ")");
        }

        JsonObject claims = StrictJson.object(payloadBytes, "token payload");
        if (!aIssuer.issuer().equals(IssuerFiles.string(claims, "iss"))) {
            throw new JwtRejectedException("iss is not " + aIssuer.issuer());
        }
        if (!audienceMatches(claims.get("aud"), aAudience)) {
            throw new JwtRejectedException("aud is not " + aAudience);
        }

        long now = aNow.getEpochSecond();
        long exp = seconds(claims, "exp");
        long nbf = seconds(claims, "nbf");
        long iat = seconds(claims, "iat");
        if (exp <= now - SKEW_SECONDS) {
            throw new JwtRejectedException("expired");
        }
        if (nbf > now + SKEW_SECONDS) {
            throw new JwtRejectedException("not valid yet (nbf)");
        }
        if (iat > now + SKEW_SECONDS) {
            throw new JwtRejectedException("issued in the future (iat)");
        }
        if (exp <= iat || exp - iat > MAX_LIFETIME) {
            throw new JwtRejectedException("lifetime exp - iat out of (0, 24h]");
        }
        if (now - iat > aMaxAge.toSeconds()) {
            throw new JwtRejectedException("older than maxTokenAge " + aMaxAge);
        }
        return claims;
    }

    private static byte[] decode(String aPart, String aWhat) throws JwtRejectedException {
        byte[] bytes = IssuerFiles.Base64Url.decode(aPart);
        if (bytes == null || bytes.length == 0) {
            throw new JwtRejectedException(aWhat + " is not canonical base64url");
        }
        return bytes;
    }

    private static boolean audienceMatches(JsonElement aAud, String aAudience) {
        if (aAud == null) {
            return false;
        }
        if (aAud.isJsonPrimitive() && aAud.getAsJsonPrimitive().isString()) {
            return aAudience.equals(aAud.getAsString());
        }
        if (aAud.isJsonArray() && !aAud.getAsJsonArray().isEmpty()) {
            boolean found = false;
            for (JsonElement item : aAud.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    return false;
                }
                found |= aAudience.equals(item.getAsString());
            }
            return found;
        }
        return false;
    }

    /** A NumericDate: a JSON integer literal (no fraction, no exponent) of a sane size. */
    private static long seconds(JsonObject aClaims, String aName) throws JwtRejectedException {
        JsonElement element = aClaims.get(aName);
        if (element == null || !element.isJsonPrimitive() || !((JsonPrimitive) element).isNumber()
                || !INTEGER.matcher(element.getAsString()).matches()) {
            throw new JwtRejectedException(aName + " is missing or not an integer");
        }
        long value;
        try {
            value = Long.parseLong(element.getAsString()); // INTEGER bounds the digits: cannot overflow
        } catch (NumberFormatException e) {
            throw new JwtRejectedException(aName + " is not an integer");
        }
        if (value < 0) {
            throw new JwtRejectedException(aName + " is negative");
        }
        return value;
    }
}
