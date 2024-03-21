package com.payneteasy.dcagent.controller.service.config.impl;


import com.payneteasy.dcagent.controller.service.config.IControllerConfigService;
import com.payneteasy.dcagent.controller.service.config.model.TAgentHost;
import org.junit.Test;

import java.io.File;

import static com.google.common.truth.Truth.assertThat;

public class ControllerConfigServiceImplTest {

    private final IControllerConfigService configService = new ControllerConfigServiceImpl(new File("src/test/resources/controller-config-test.yaml"));

    @Test
    public void test() {
        TAgentHost host = configService.findAgentHost("host-2").orElseThrow(() -> new IllegalArgumentException("No host host-2"));
        assertThat(host).isNotNull();
        assertThat(host.getName() ).isEqualTo("host-2");
        assertThat(host.getUrl()  ).isEqualTo("http://localhost:8052/dc-agent");
        assertThat(host.getToken()).isEqualTo("09bc0362-4a21-4d78-a13b-6a7c785e8f22");
    }
}