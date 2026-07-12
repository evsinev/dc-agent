package com.payneteasy.dcagent.core.remote.agent.appstatus;

import com.google.gson.Gson;
import com.payneteasy.http.client.api.HttpHeader;
import com.payneteasy.http.client.api.HttpHeaders;
import com.payneteasy.http.client.api.HttpMethod;
import com.payneteasy.http.client.api.HttpRequest;
import com.payneteasy.http.client.api.HttpRequestParameters;
import com.payneteasy.http.client.api.HttpResponse;
import com.payneteasy.http.client.api.IHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;

/**
 * Reads a single agent's app status via {@code GET {baseUrl}/app-status/},
 * authenticated with {@code Authorization: Bearer <appStatusToken>}.
 */
public class AgentAppStatusClient {

    private static final Logger LOG = LoggerFactory.getLogger(AgentAppStatusClient.class);

    private final IHttpClient           httpClient;
    private final String                baseUrl;
    private final String                bearerToken;
    private final HttpRequestParameters requestParameters;
    private final Gson                  gson;

    public AgentAppStatusClient(IHttpClient httpClient, String baseUrl, String bearerToken, HttpRequestParameters requestParameters, Gson gson) {
        this.httpClient        = httpClient;
        this.baseUrl           = baseUrl;
        this.bearerToken       = bearerToken;
        this.requestParameters = requestParameters;
        this.gson              = gson;
    }

    public TAgentAppStatus fetch() {
        String url = baseUrl + "/app-status/";

        LOG.debug(">> GET {} ...", url);

        HttpRequest request = HttpRequest.builder()
                .url(url)
                .method(HttpMethod.GET)
                .headers(new HttpHeaders(singletonList(
                        new HttpHeader("Authorization", "Bearer " + bearerToken)
                )))
                .build();

        HttpResponse response;
        try {
            response = httpClient.send(request, requestParameters);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot get app-status from " + url, e);
        }

        LOG.debug("<< GET {} {}", url, response.getStatusCode());

        String json = new String(response.getBody(), UTF_8);
        if (response.getStatusCode() != 200) {
            throw new IllegalStateException("Bad app-status response " + response.getStatusCode() + " " + json);
        }

        return gson.fromJson(json, TAgentAppStatus.class);
    }
}
