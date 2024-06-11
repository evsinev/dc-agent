package com.payneteasy.dcagent.core.remote.agent.controlplane.client;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;
import com.payneteasy.http.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

public class DcAgentControlPlaneClient implements IDcAgentControlPlaneRemoteService {

    private static final Logger LOG = LoggerFactory.getLogger( DcAgentControlPlaneClient.class );

    private final IHttpClient           httpClient;
    private final String                baseUrl;
    private final String                bearerToken;
    private final HttpRequestParameters requestParameters;
    private final Gson                  gson;

    public DcAgentControlPlaneClient(IHttpClient httpClient, String baseUrl, String bearerToken, HttpRequestParameters requestParameters, Gson gson) {
        this.httpClient        = httpClient;
        this.baseUrl           = baseUrl;
        this.bearerToken       = bearerToken;
        this.requestParameters = requestParameters;
        this.gson              = gson;
    }

    @Override
    public ServiceListResponse listServices(ServiceListRequest aRequest) {
        return post("/control-plane/api/service/list", aRequest, ServiceListResponse.class);
    }

    @Override
    public ServiceViewResponse viewService(ServiceViewRequest aRequest) {
        return post("/control-plane/api/service/view/" + aRequest.getServiceName(), aRequest, ServiceViewResponse.class);
    }

    private <T> T post(String aPath, Object aRequest, Class<T> aResponseClass) {
        String url  = baseUrl + aPath;
        String json = gson.toJson(aRequest);

        LOG.debug(">> POST {} ...", url);

        HttpRequest request = HttpRequest.builder()
                .url(url)
                .method(HttpMethod.POST)
                .headers(new HttpHeaders(
                        asList(
                                  new HttpHeader("Authorization", "Bearer " + bearerToken)
                                , new HttpHeader("Content-type", "application/json")
                        )
                ))
                .body(json.getBytes(UTF_8))
                .build();


        HttpResponse response;
        try {
            response = httpClient.send(request, requestParameters);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot send task to " + url, e);
        }

        LOG.debug("<< POST {} {}", url, response.getStatusCode());

        String jsonResponse = new String(response.getBody(), UTF_8);
        if (response.getStatusCode() != 200) {
            throw new IllegalStateException("Bad response status " + response.getStatusCode() + " " + jsonResponse);
        }

        return gson.fromJson(jsonResponse, aResponseClass);
    }


}
