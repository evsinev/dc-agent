package com.payneteasy.dcagent.core.task.send.impl;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.SendTaskParam;
import com.payneteasy.dcagent.core.task.send.SendTaskResult;
import com.payneteasy.http.client.api.HttpRequest;
import com.payneteasy.http.client.api.HttpResponse;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.EMPTY_LIST;


public class SendTaskServiceImplTest {

    @Test
    public void send_task() {
        MockHttpClientImpl httpClient = new MockHttpClientImpl(new HttpResponse(200, "OK", EMPTY_LIST, "response-1".getBytes(UTF_8)));

        ISendTaskService   service = new SendTaskServiceImpl(httpClient);
        SendTaskResult     result = service.sendTask(SendTaskParam.builder()
                        .taskBytes("request-1".getBytes(UTF_8))
                        .accessToken("token-1")
                        .agentBaseUrl("http://localhost:8082/dc-agent")
                        .taskType(TaskType.DOCKER_CHECK)
                        .taskName("task-1")
                .build());

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getText()).isEqualTo("response-1");

        HttpRequest request = httpClient.getRequest();
        assertThat(request.getUrl()).isEqualTo("http://localhost:8082/dc-agent/docker/check/task-1");
        assertThat(request.getHeaders().asList().size()).isEqualTo(2);

    }
}