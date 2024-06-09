package com.payneteasy.dcagent.admin.service.daemontools.impl;

import com.payneteasy.dcagent.controlplane.service.daemontools.impl.DaemontoolsServiceImpl;
import org.junit.Test;

import java.io.File;


public class DaemontoolsServiceImplTest {

    @Test
    public void service_info() {
        DaemontoolsServiceImpl service = new DaemontoolsServiceImpl(new File("src/test/resources/services"));

    }
}