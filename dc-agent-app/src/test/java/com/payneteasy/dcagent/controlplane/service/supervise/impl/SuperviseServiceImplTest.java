package com.payneteasy.dcagent.controlplane.service.supervise.impl;

import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.util.SimpleLogImpl;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;


public class SuperviseServiceImplTest {

    @Test
    public void service_info() {
        SuperviseServiceImpl service = new SuperviseServiceImpl(
                new File("src/test/resources/services")
                , new DaemontoolsServiceImpl("svc", "svstat", new SimpleLogImpl(SuperviseServiceImplTest.class))
        );

        assertThat(service).isNotNull();
    }
}