package com.payneteasy.dcagent.core.remote.agent.appstatus;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.http.client.api.HttpRequestParameters;
import com.payneteasy.http.client.api.HttpTimeouts;
import com.payneteasy.http.client.api.IHttpClient;

public class AgentAppStatusClientFactory {

    private final Gson gson = Gsons.PRETTY_GSON;

    private final HttpRequestParameters requestParameters = HttpRequestParameters.builder()
            .timeouts(new HttpTimeouts(3_000, 5_000))
            .build();

    private final IHttpClient httpClient;

    public AgentAppStatusClientFactory(IHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public AgentAppStatusClient createClient(
            String baseUrl
            , String bearerToken
    ) {
        return new AgentAppStatusClient(
                httpClient
                , baseUrl
                , bearerToken
                , requestParameters
                , gson
        );
    }
}
