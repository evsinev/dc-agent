package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.config.model.IApiKeys;
import com.payneteasy.dcagent.exception.WrongApiKeyException;
import com.payneteasy.dcagent.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Map;

public class CheckApiKey {

    private static final Logger LOG = LoggerFactory.getLogger(CheckApiKey.class);

    public void check(HttpServletRequest aRequest, IApiKeys aKeys) {
        String apiKey  = findKey(aRequest);

        if(Strings.isEmpty(apiKey)) {
            LOG.warn("Cannot find auth header in {}", dumpHeaders(aRequest) );
            throw new WrongApiKeyException("No header api-key or Authorization");
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

    private String dumpHeaders(HttpServletRequest aRequest) {
        StringBuilder sb = new StringBuilder();
        Enumeration<String> headers = aRequest.getHeaderNames();
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            sb.append(header);
            sb.append(" = ");
            sb.append(aRequest.getHeader(header));
            sb.append("\n");
        }
        return sb.toString();
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
        String base64 = aBasicAuth.split(" ")[1];
        String plain = new String(Base64.getDecoder().decode(base64));
        return plain.split(":")[1];
    }
}
