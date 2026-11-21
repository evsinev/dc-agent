package com.payneteasy.dcagent.jetty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.auth.gitlab.GitlabRules;
import com.payneteasy.dcagent.core.auth.gitlab.GitlabTokenVerifier;
import com.payneteasy.dcagent.core.auth.gitlab.IssuerFiles;
import com.payneteasy.dcagent.core.auth.gitlab.IssuerKeys;
import com.payneteasy.dcagent.core.auth.gitlab.JwtRejectedException;
import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.config.model.TCommandKey;
import com.payneteasy.dcagent.core.config.model.TKeyType;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import com.payneteasy.dcagent.core.config.validation.CommandKeys;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.core.util.Units;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Checks the request's credentials against a command config:
 * <ul>
 *     <li>{@code Authorization: Bearer <jwt>} — a GitLab CI/CD ID token, checked only against the
 *     {@code GITLAB_JWT} entries of {@code keys} (issuer file, signature, aud, time, runnerIds, rules);</li>
 *     <li>{@code api-key: <key>} or the password of {@code Authorization: Basic} — a static key, checked
 *     only against {@code apiKeys} and the {@code SHA256} entries of {@code keys}.</li>
 * </ul>
 * The header decides the kind: a token is never compared as a static key nor the other way round. Two
 * {@code Authorization} or two {@code api-key} headers, or a Bearer token next to an {@code api-key},
 * are refused. Every rejection is the same {@link WrongApiKeyException} with {@link #UNAUTHORIZED}; the
 * reason goes to the agent log only (never a key or a token). An accepted token is logged for audit.
 */
public class CheckApiKey {

    private static final Logger LOG = LoggerFactory.getLogger(CheckApiKey.class);

    public static final String UNAUTHORIZED = "Unauthorized";

    private static final String AUTHORIZATION = "Authorization";
    private static final String API_KEY       = "api-key";

    private final Path  keysDir;
    private final Clock clock;

    /** No issuer files: every GitLab token is refused. */
    public CheckApiKey() {
        this(null, Clock.systemUTC());
    }

    public CheckApiKey(Path aKeysDir, Clock aClock) {
        keysDir = aKeysDir;
        clock   = aClock;
    }

    public void check(HttpServletRequest aRequest, IApiKeys aKeys) {
        String reason = rejectReason(aRequest, aKeys);
        if (reason != null) {
            LOG.warn("Rejected request: {}", Strings.forLog(reason));
            throw new WrongApiKeyException(UNAUTHORIZED);
        }
    }

    /** True when the request carries a credential at all (a Bearer token, an api-key or a Basic password). */
    boolean hasCredentials(HttpServletRequest aRequest) {
        return bearerToken(aRequest) != null || Strings.hasText(findKey(aRequest));
    }

    /** Why the request does not fit {@code aKeys}, or null when it does. For the log only. */
    String rejectReason(HttpServletRequest aRequest, IApiKeys aKeys) {
        return rejectReason(aRequest, aKeys, "?");
    }

    /** As {@link #rejectReason(HttpServletRequest, IApiKeys)}; {@code aCommand} names the command in the audit line. */
    String rejectReason(HttpServletRequest aRequest, IApiKeys aKeys, String aCommand) {
        List<String> authorizations = headers(aRequest, AUTHORIZATION);
        List<String> apiKeyHeaders  = headers(aRequest, API_KEY);
        if (authorizations.size() > 1 || apiKeyHeaders.size() > 1) {
            return "repeated Authorization or api-key header";
        }
        String token = bearerToken(aRequest);
        if (token != null) {
            if (!apiKeyHeaders.isEmpty()) {
                return "both a Bearer token and an api-key";
            }
            return gitlabReason(aRequest, token, aKeys, aCommand);
        }
        return staticReason(aRequest, aKeys);
    }

    private String gitlabReason(HttpServletRequest aRequest, String aToken, IApiKeys aKeys, String aCommand) {
        List<TCommandKey> jwtKeys = new ArrayList<>();
        if (aKeys.getKeys() != null) {
            for (TCommandKey key : aKeys.getKeys()) {
                if (key != null && key.getType() == TKeyType.GITLAB_JWT) {
                    jwtKeys.add(key);
                }
            }
        }
        if (jwtKeys.isEmpty()) {
            return "a Bearer token, but no GITLAB_JWT key configured for this command";
        }
        if (keysDir == null) {
            return "a Bearer token, but this agent has no keys directory";
        }
        List<String> reasons = new ArrayList<>();
        for (TCommandKey key : jwtKeys) {
            try {
                IssuerKeys issuer = IssuerFiles.load(keysDir, key.getIssuer());
                Duration   maxAge = key.getMaxTokenAge() == null ? CommandKeys.DEFAULT_MAX_TOKEN_AGE : Units.parseDuration(key.getMaxTokenAge());
                JsonObject claims = GitlabTokenVerifier.verify(aToken, issuer, key.getAudience(), maxAge, clock.instant());
                int        rule   = GitlabRules.match(claims, key);
                LOG.info("Authorized command {} by GitLab token: key {}, rule {}, issuer {}, project {} ({}), ref {} ({}), pipeline {} {}, job {}, user {}, jti {}, request {}",
                        Strings.forLog(aCommand), key.getName(), rule, issuer.issuer(),
                        claim(claims, "project_id"), claim(claims, "project_path"), claim(claims, "ref"), claim(claims, "ref_type"),
                        claim(claims, "pipeline_id"), claim(claims, "pipeline_source"), claim(claims, "job_id"),
                        claim(claims, "user_login"), claim(claims, "jti"), aRequest.getAttribute(AuditFilter.REQUEST_ID));
                aRequest.setAttribute(AuditFilter.AUTHORIZED_BY, "GitLab token (key " + key.getName() + ", project "
                        + claim(claims, "project_path") + ", ref " + claim(claims, "ref") + ", job " + claim(claims, "job_id") + ")");
                return null;
            } catch (JwtRejectedException | RuntimeException e) {
                reasons.add("key " + key.getName() + ": " + (e instanceof JwtRejectedException ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        return "GitLab token refused — " + String.join("; ", reasons);
    }

    /** A claim for the audit line: its string form, cut and cleaned for the log; "-" when absent. */
    private static String claim(JsonObject aClaims, String aName) {
        JsonElement element = aClaims.get(aName);
        if (element == null || !element.isJsonPrimitive()) {
            return "-";
        }
        String value = element.getAsString();
        return Strings.forLog(value.length() > 200 ? value.substring(0, 200) : value);
    }

    private String staticReason(HttpServletRequest aRequest, IApiKeys aKeys) {
        String apiKey = findKey(aRequest);
        if (Strings.isEmpty(apiKey)) {
            return "no api-key/Authorization Basic key in the request";
        }

        Collection<String> hashes = staticHashes(aKeys);
        if (hashes.isEmpty()) {
            return "no static keys (apiKeys or keys of type SHA256) configured for this command";
        }

        // only the hash of the presented key is compared, and only with hashed entries
        if (ApiKeyHash.matches(apiKey, hashes)) {
            aRequest.setAttribute(AuditFilter.AUTHORIZED_BY, "static key " + Strings.forLog(staticName(aKeys, ApiKeyHash.hash(apiKey)))
                    + " " + ApiKeyHash.maskedId(apiKey));
            return null;
        }
        long plaintext = hashes.stream().filter(ApiKeyHash::isPlaintext).count();
        return plaintext == 0 ? "api key not found"
                : "api key not found; the config has " + plaintext + " plaintext key(s), which are never accepted:"
                  + " restart the agent to hash them, or store sha256:<hex> (java -jar dc-agent.jar hash-key)";
    }

    /** The name of the static entry with this hash — a SHA256 entry's name or an apiKeys owner — for the audit line. */
    static String staticName(IApiKeys aKeys, String aHash) {
        if (aKeys.getKeys() != null) {
            for (TCommandKey key : aKeys.getKeys()) {
                if (key != null && key.getType() == TKeyType.SHA256 && aHash.equals(key.getHash())) {
                    return key.getName();
                }
            }
        }
        if (aKeys.getApiKeys() != null && aKeys.getApiKeys().get(aHash) != null) {
            String owner = aKeys.getApiKeys().get(aHash);
            return owner.length() > 64 ? owner.substring(0, 64) : owner;
        }
        return "-";
    }

    /** The token of {@code Authorization: Bearer <token>}, or null for no such header. */
    static String bearerToken(HttpServletRequest aRequest) {
        String auth = aRequest.getHeader(AUTHORIZATION);
        if (auth == null) {
            return null;
        }
        String[] parts = auth.trim().split(" ", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : null;
    }

    /** Every value of a header (a request without getHeaders support: the single getHeader value). */
    private static List<String> headers(HttpServletRequest aRequest, String aName) {
        List<String> values = new ArrayList<>();
        Enumeration<String> all = aRequest.getHeaders(aName);
        if (all != null) {
            while (all.hasMoreElements()) {
                values.add(all.nextElement());
            }
        } else if (aRequest.getHeader(aName) != null) {
            values.add(aRequest.getHeader(aName));
        }
        return values;
    }

    /** The stored static keys: the legacy apiKeys entries, or the hashes of the SHA256 entries of keys. */
    static Collection<String> staticHashes(IApiKeys aKeys) {
        List<String> hashes = new ArrayList<>();
        Map<String, String> apiKeys = aKeys.getApiKeys();
        if (apiKeys != null) {
            hashes.addAll(apiKeys.keySet());
        }
        List<TCommandKey> keys = aKeys.getKeys();
        if (keys != null) {
            for (TCommandKey key : keys) {
                if (key != null && key.getType() == TKeyType.SHA256 && key.getHash() != null) {
                    hashes.add(key.getHash());
                }
            }
        }
        return hashes;
    }

    /** The key from the request, or null when there is none (or the Authorization header is unusable). */
    String findKey(HttpServletRequest aRequest) {
        String apiKey = aRequest.getHeader("api-key");
        if (Strings.hasText(apiKey)) {
            return apiKey;
        }

        String auth = aRequest.getHeader(AUTHORIZATION);
        if (Strings.isEmpty(auth)) {
            return null;
        }

        return parseBasisAuth(auth);
    }

    /** Password from {@code Basic base64(user:password)}; null for another scheme, bad Base64 or no ':'. */
    static String parseBasisAuth(String aBasicAuth) {
        String[] parts = aBasicAuth.trim().split("\\s+", 2);
        if (parts.length < 2 || !"Basic".equalsIgnoreCase(parts[0])) {
            return null;
        }
        String plain;
        try {
            plain = new String(Base64.getDecoder().decode(parts[1].trim()), UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] creds = plain.split(":", 2);
        return creds.length < 2 ? null : creds[1];
    }
}
