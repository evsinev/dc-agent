package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Map;

public class CheckApiKey {

    private static final Logger LOG = LoggerFactory.getLogger(CheckApiKey.class);

    public void check(HttpServletRequest aRequest, IApiKeys aKeys) {
        String apiKey  = findKey(aRequest);

        if(Strings.isEmpty(apiKey)) {
            LOG.warn("Rejected request: no api-key/Authorization header");
            throw new WrongApiKeyException("No header api-key or Authorization");
        }

        Map<String, String> apiKeys = aKeys.getApiKeys();
        if(apiKeys == null) {
            throw new WrongApiKeyException("No api-keys configured for this command");
        }

        if(!apiKeys.containsKey(apiKey)) {
            throw new WrongApiKeyException("API key not found");
        }

    }

    private String findKey(HttpServletRequest aRequest) {
        String apiKey = aRequest.getHeader("api-key");
        if(Strings.hasText(apiKey)) {
            return apiKey;
        }

        String auth = aRequest.getHeader("Authorization");
        if(Strings.isEmpty(auth)) {
            return null;
        }

        return parseBasisAuth(auth);
    }

    static String parseBasisAuth(String aBasicAuth) {
        String[] parts = aBasicAuth.split(" ");
        if (parts.length < 2) {
            return null;
        }
        String   plain = new String(Base64.getDecoder().decode(parts[1]));
        String[] creds = plain.split(":");
        return creds.length < 2 ? null : creds[1];
    }
}
