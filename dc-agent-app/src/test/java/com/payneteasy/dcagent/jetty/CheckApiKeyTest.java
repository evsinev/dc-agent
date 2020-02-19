package com.payneteasy.dcagent.jetty;

import org.junit.Assert;
import org.junit.Test;

public class CheckApiKeyTest {

    @Test
    public void parseBasisAuth() {
        Assert.assertEquals("OpenSesame", CheckApiKey.parseBasisAuth("Basic QWxhZGRpbjpPcGVuU2VzYW1l"));
    }
}