package com.payneteasy.dcagent.core.job.send.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public class SimpleX509TrustManager implements X509TrustManager {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleX509TrustManager.class);

    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        LOG.debug("checkClientTrusted(): {}, {}", Arrays.asList(chain), authType);
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        LOG.debug("checkServerTrusted(): {}, {}", Arrays.asList(chain), authType);
    }

    public X509Certificate[] getAcceptedIssuers() {
        LOG.debug("getAcceptedIssuers()");
        return new X509Certificate[]{};
    }
}