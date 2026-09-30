package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Checks the request key ({@code api-key} header, else the password of {@code Authorization: Basic})
 * against a command config. Every rejection is the same {@link WrongApiKeyException} with
 * {@link #UNAUTHORIZED}; the reason goes to the agent log only (never the key itself).
 */
public class CheckApiKey {

    private static final Logger LOG = LoggerFactory.getLogger(CheckApiKey.class);

    public static final String UNAUTHORIZED = "Unauthorized";

    public void check(HttpServletRequest aRequest, IApiKeys aKeys) {
        String reason = rejectReason(aRequest, aKeys);
        if (reason != null) {
            LOG.warn("Rejected request: {}", reason);
            throw new WrongApiKeyException(UNAUTHORIZED);
        }
    }

    /** Why the request key does not fit {@code aKeys}, or null when it does. For the log only. */
    String rejectReason(HttpServletRequest aRequest, IApiKeys aKeys) {
        String apiKey = findKey(aRequest);
        if (Strings.isEmpty(apiKey)) {
            return "no api-key/Authorization Basic key in the request";
        }

        Map<String, String> apiKeys = aKeys.getApiKeys();
        if (apiKeys == null || apiKeys.isEmpty()) {
            return "no apiKeys configured for this command";
        }

        return apiKeys.containsKey(apiKey) ? null : "api key not found";
    }

    /** The key from the request, or null when there is none (or the Authorization header is unusable). */
    String findKey(HttpServletRequest aRequest) {
        String apiKey = aRequest.getHeader("api-key");
        if (Strings.hasText(apiKey)) {
            return apiKey;
        }

        String auth = aRequest.getHeader("Authorization");
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
