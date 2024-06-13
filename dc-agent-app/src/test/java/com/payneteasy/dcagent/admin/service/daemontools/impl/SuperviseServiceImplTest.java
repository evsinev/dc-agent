package com.payneteasy.dcagent.admin.service.daemontools.impl;

import com.payneteasy.dcagent.controlplane.service.supervise.impl.SuperviseServiceImpl;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.util.SimpleLogImpl;
import org.junit.Test;

import java.io.File;


public class SuperviseServiceImplTest {

    @Test
    public void service_info() {
        SuperviseServiceImpl service = new SuperviseServiceImpl(
                new File("src/test/resources/services")
                , new DaemontoolsServiceImpl("svc", "svstat", new SimpleLogImpl(SuperviseServiceImplTest.class))
        );

    }
}