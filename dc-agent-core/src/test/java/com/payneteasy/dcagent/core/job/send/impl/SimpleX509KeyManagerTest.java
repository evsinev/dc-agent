package com.payneteasy.dcagent.core.job.send.impl;

import com.payneteasy.dcagent.core.util.SecureKeys;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

public class SimpleX509KeyManagerTest {

    private static final String ALIAS = "client";

    private SimpleX509KeyManager manager;
    private PrivateKey privateKey;
    private X509Certificate certificate;

    @Before
    public void setUp() {
        SecureKeys keys = new SecureKeys();
        privateKey = keys.loadPrivateKeyFile(new File("src/test/resources/dc-agent-config/client.key"));
        certificate = keys.loadCertificate(new File("src/test/resources/dc-agent-config/client.crt"));
        manager = new SimpleX509KeyManager(ALIAS, privateKey, certificate);
    }

    @Test
    public void get_client_aliases_returns_the_configured_alias() {
        assertThat(manager.getClientAliases("RSA", new java.security.Principal[0])).containsExactly(ALIAS);
    }

    @Test
    public void choose_client_alias_returns_the_configured_alias() {
        assertThat(manager.chooseClientAlias(new String[]{"RSA"}, new java.security.Principal[0], null)).isEqualTo(ALIAS);
    }

    @Test
    public void server_aliases_are_empty() {
        assertThat(manager.getServerAliases("RSA", new java.security.Principal[0])).isEmpty();
    }

    @Test
    public void choose_server_alias_is_null() {
        assertThat(manager.chooseServerAlias("RSA", new java.security.Principal[0], null)).isNull();
    }

    @Test
    public void certificate_chain_contains_the_client_certificate() {
        assertThat(manager.getCertificateChain(ALIAS)).containsExactly(certificate);
    }

    @Test
    public void private_key_is_returned() {
        assertThat(manager.getPrivateKey(ALIAS)).isSameAs(privateKey);
    }
}
