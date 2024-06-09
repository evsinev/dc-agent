package com.payneteasy.dcagent.core.remote.agent.controlplane.client;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.http.client.api.HttpRequestParameters;
import com.payneteasy.http.client.api.HttpTimeouts;
import com.payneteasy.http.client.api.IHttpClient;

public class DcAgentControlPlaneClientFactory {

    private final Gson gson = Gsons.PRETTY_GSON;

    private final HttpRequestParameters requestParameters = HttpRequestParameters.builder()
            .timeouts(new HttpTimeouts(10_000, 60_000))
            .build();

    private final IHttpClient httpClient;

    public DcAgentControlPlaneClientFactory(IHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public IDcAgentControlPlaneRemoteService createClient(
            String baseUrl
            , String bearerToken
    ) {
        return new DcAgentControlPlaneClient(
                httpClient
                , baseUrl
                , bearerToken
                , requestParameters
                , gson
        );
    }
}
