package com.payneteasy.dcagent.core.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SsrfGuardTest {

    @Test
    public void allows_public_http_and_https() {
        // IP literals: no DNS lookup, so these are hermetic.
        assertEquals("8.8.8.8", SsrfGuard.validate("http://8.8.8.8/artifact.zip").getHost());
        assertEquals("93.184.216.34", SsrfGuard.validate("https://93.184.216.34/x").getHost());
    }

    @Test
    public void blocks_non_http_schemes() {
        assertBlocked("file:///etc/passwd");
        assertBlocked("ftp://8.8.8.8/x");
        assertBlocked("gopher://8.8.8.8/");
    }

    @Test
    public void blocks_loopback_metadata_and_private() {
        assertBlocked("http://127.0.0.1/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://169.254.169.254/latest/meta-data/");  // cloud metadata
        assertBlocked("http://10.0.0.1/");
        assertBlocked("http://172.16.0.1/");
        assertBlocked("http://192.168.1.1/");
        assertBlocked("http://0.0.0.0/");
    }

    @Test
    public void blocks_malformed_and_hostless() {
        assertBlocked("not a url");
        assertBlocked("http:///no-host");
    }

    private static void assertBlocked(String aUrl) {
        try {
            SsrfGuard.validate(aUrl);
            fail("Expected SsrfBlockedException for " + aUrl);
        } catch (SsrfBlockedException expected) {
            // expected
        }
    }
}
