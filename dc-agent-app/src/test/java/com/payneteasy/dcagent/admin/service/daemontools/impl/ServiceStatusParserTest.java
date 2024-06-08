package com.payneteasy.dcagent.admin.service.daemontools.impl;


import com.payneteasy.dcagent.admin.service.daemontools.model.ServiceStatus;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class ServiceStatusParserTest {

    private static final Logger LOG = LoggerFactory.getLogger( ServiceStatusParserTest.class );

    private final ServiceStatusParser parser = new ServiceStatusParser();

    @Test
    public void test() {
        ServiceStatus status = parser.parseServiceStatus(new File("src/test/resources/services/test-1"));
        LOG.debug("Status is {}", status);
    }

}