package com.payneteasy.dcagent;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** A test issuer: an RSA key pair, its JWKS, and tokens signed with any header and payload text. */
public final class TestTokens {

    public static final String ISSUER = "https://gitlab.test/git";
    public static final String KID    = "test-kid";

    public final KeyPair pair;

    public TestTokens(int aBits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(aBits);
            pair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String b64(byte[] aBytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(aBytes);
    }

    public static String b64(String aText) {
        return b64(aText.getBytes(StandardCharsets.UTF_8));
    }

    private static String unsigned(BigInteger aValue) {
        byte[] bytes = aValue.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return b64(bytes);
    }

    public String jwk(String aKid) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        return "{\"kty\":\"RSA\",\"kid\":\"" + aKid + "\",\"n\":\"" + unsigned(key.getModulus()) + "\",\"e\":\""
                + unsigned(key.getPublicExponent()) + "\",\"alg\":\"RS256\",\"use\":\"sig\"}";
    }

    public String issuerFile(String aFetchedAt) {
        return "{\"issuer\":\"" + ISSUER + "\"," + (aFetchedAt == null ? "" : "\"fetchedAt\":\"" + aFetchedAt + "\",")
                + "\"jwks\":{\"keys\":[" + jwk(KID) + "]}}";
    }

    public String sign(String aHeaderJson, String aPayloadJson) {
        String input = b64(aHeaderJson) + "." + b64(aPayloadJson);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String header() {
        return "{\"kid\":\"" + KID + "\",\"typ\":\"JWT\",\"alg\":\"RS256\"}";
    }

    /** A GitLab-like payload issued at {@code aIat}, valid an hour; {@code aExtra} adds or overrides nothing (appended). */
    public static String payload(long aIat, String aExtra) {
        return "{\"aud\":\"dc-agent:test\",\"iss\":\"" + ISSUER + "\",\"iat\":" + aIat + ",\"nbf\":" + (aIat - 5) + ",\"exp\":" + (aIat + 3600)
                + ",\"project_id\":\"913\",\"project_path\":\"payneteasy/paynet-assets\",\"ref\":\"main\",\"ref_type\":\"branch\""
                + ",\"ref_protected\":\"true\",\"pipeline_source\":\"push\",\"pipeline_id\":\"1\",\"job_id\":\"2\",\"runner_id\":26"
                + ",\"user_login\":\"es\",\"jti\":\"j-1\"" + aExtra + "}";
    }
}
