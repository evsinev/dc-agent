package com.payneteasy.dcagent.core.auth.gitlab;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;

/**
 * An issuer file read: the pinned {@code iss}, its signing keys by {@code kid}, and when
 * {@code fetch-jwks} last wrote it (null when it was written by hand).
 */
public record IssuerKeys(String name, String issuer, Map<String, RSAPublicKey> keys, Instant fetchedAt) {
}
