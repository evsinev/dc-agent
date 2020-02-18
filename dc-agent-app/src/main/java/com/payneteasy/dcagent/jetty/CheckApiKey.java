package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.config.model.IApiKeys;
import com.payneteasy.dcagent.exception.WrongApiKeyException;
import com.payneteasy.dcagent.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

public class CheckApiKey {

    private static final Logger LOG = LoggerFactory.getLogger(CheckApiKey.class);

    public void check(HttpServletRequest aRequest, IApiKeys aKeys) {
        String              apiKey  = aRequest.getHeader("api-key");

        if(Strings.isEmpty(apiKey)) {
            throw new WrongApiKeyException("No header api-key");
        }

        Map<String, String> apiKeys = aKeys.getApiKeys();
        if(apiKeys == null) {
            LOG.warn("No api keys in {}", aKeys);
            return;
        }

        if(!apiKeys.containsKey(apiKey)) {
            throw new WrongApiKeyException("API key not found");
        }

    }
}
