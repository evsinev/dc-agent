package com.payneteasy.dcagent.core.task.send.impl;

import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.SendTaskParam;
import com.payneteasy.dcagent.core.task.send.SendTaskResult;
import com.payneteasy.http.client.api.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

public class SendTaskServiceImpl implements ISendTaskService {

    private final IHttpClient httpClient;

    public SendTaskServiceImpl(IHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public SendTaskResult sendTask(SendTaskParam aParam) {
        String url = buildUrl(aParam);

        HttpRequest request = HttpRequest.builder()
                .url(url)
                .method  ( HttpMethod.POST )
                .headers (new HttpHeaders(
                    asList(
                          new HttpHeader("api-key"     , aParam.getAccessToken()    )
                        , new HttpHeader("Content-type", "application/octet-stream" )
                    )
                ))
                .body(aParam.getTaskBytes())
                .build();

        HttpRequestParameters requestParameters = HttpRequestParameters.builder()
                .timeouts(new HttpTimeouts(10_000, 60_000))
                .build();


        HttpResponse response;
        try {
            response = httpClient.send(request, requestParameters);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot send task to " + url, e);
        }

        return SendTaskResult.builder()
                .statusCode(response.getStatusCode())
                .text(new String(response.getBody(), UTF_8))
                .build();
    }

    private String buildUrl(SendTaskParam aParam) {
        String segment;
        switch (aParam.getTaskType()) {
            case DOCKER_PUSH : segment = "/docker/push/" ; break;
            case DOCKER_CHECK: segment = "/docker/check/"; break;
            default:
                throw new IllegalStateException("Task type " + aParam.getTaskType() + " is unsupported yet");
        }
        return aParam.getAgentBaseUrl() + segment + aParam.getTaskName();
    }
}
