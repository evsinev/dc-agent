package com.payneteasy.dcagent.core.task.send.impl;

import com.payneteasy.http.client.api.HttpRequest;
import com.payneteasy.http.client.api.HttpRequestParameters;
import com.payneteasy.http.client.api.HttpResponse;
import com.payneteasy.http.client.api.IHttpClient;
import com.payneteasy.http.client.api.exceptions.HttpConnectException;
import com.payneteasy.http.client.api.exceptions.HttpReadException;
import com.payneteasy.http.client.api.exceptions.HttpWriteException;
import lombok.Getter;

public class MockHttpClientImpl implements IHttpClient {

    @Getter
    private HttpRequest           request;
    @Getter
    private HttpRequestParameters requestParameters;

    private final HttpResponse response;

    public MockHttpClientImpl(HttpResponse response) {
        this.response = response;
    }

    @Override
    public HttpResponse send(HttpRequest aRequest, HttpRequestParameters aRequestParameters) throws HttpConnectException, HttpReadException, HttpWriteException {
        request = aRequest;
        requestParameters = aRequestParameters;
        return response;
    }

}
