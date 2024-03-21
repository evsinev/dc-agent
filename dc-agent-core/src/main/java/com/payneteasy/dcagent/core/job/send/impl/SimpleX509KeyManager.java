package com.payneteasy.dcagent.core.job.send.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public class SimpleX509KeyManager implements X509KeyManager {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleX509KeyManager.class);

    private final String          alias;
    private final PrivateKey      privateKey;
    private final X509Certificate clientCertificate;

    public SimpleX509KeyManager(String alias, PrivateKey privateKey, X509Certificate clientCertificate) {
        this.alias             = alias;
        this.privateKey        = privateKey;
        this.clientCertificate = clientCertificate;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        LOG.debug("getClientAliases() {}, {}", keyType, Arrays.asList(issuers));
        return new String[]{alias};
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        LOG.debug("chooseClientAlias() keyType = {}, issuers = {}, socket = {}", keyType, Arrays.asList(issuers), socket);
        return alias;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        LOG.debug("getServerAliases() keyType = {}, issuers = {}", keyType, Arrays.asList(issuers));
        return new String[0];
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        LOG.debug("chooseServerAlias() keyType = {}, issuers = {}, socket = {}", keyType, Arrays.asList(issuers), socket);
        return null;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        LOG.debug("getCertificateChain() alias = {}", alias);
        return new X509Certificate[] {clientCertificate};
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        LOG.debug("getPrivateKey() alias = {}", alias);
        return privateKey;
    }
}
