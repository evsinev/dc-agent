package com.payneteasy.dcagent.operator.service.info.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.info.messages.InfoResponse;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class InfoServiceImplTest {

    @Test
    public void falls_back_to_dev_when_the_class_has_no_manifest_version() {
        // A class loaded from target/classes has no Implementation-Version in its package.
        InfoResponse response = new InfoServiceImpl(InfoServiceImplTest.class).info(VoidRequest.VOID_REQUEST);
        assertEquals("dev", response.getVersion());
    }

    @Test
    public void always_returns_a_non_null_version() {
        InfoResponse response = new InfoServiceImpl(String.class).info(VoidRequest.VOID_REQUEST);
        assertNotNull(response.getVersion());
    }
}
