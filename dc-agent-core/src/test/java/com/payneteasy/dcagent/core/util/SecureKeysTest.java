package com.payneteasy.dcagent.core.util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;

import static com.google.common.truth.Truth.assertThat;


public class SecureKeysTest {

    private final SecureKeys secureKeys = new SecureKeys();

    @Test
    public void load_private_key_file() throws IOException, InvalidKeySpecException {
        PrivateKey privateKey = secureKeys.loadPrivateKeyFile(new File("src/test/resources/dc-agent-config/client.key"));

        assertThat(privateKey).isNotNull();
        assertThat(privateKey.getFormat()).isEqualTo("PKCS#8");
        assertThat(privateKey.getAlgorithm()).isEqualTo("RSA");
        assertThat(privateKey.getEncoded()).isNotNull();
        assertThat(privateKey.getEncoded().length).isEqualTo(1_216);
    }

    @Test
    public void load_public_key() {
        PublicKey publicKey = secureKeys.loadPublicKeyFromCertificate(new File("src/test/resources/dc-agent-config/client.crt"));

        assertThat(publicKey).isNotNull();
        assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
        assertThat(publicKey.getFormat()).isEqualTo("X.509");
        assertThat(publicKey.getEncoded()).isNotNull();
        assertThat(publicKey.getEncoded().length).isEqualTo(294);
    }

    @Test
    public void load_certificate() {
        X509Certificate certificate = secureKeys.loadCertificate(new File("src/test/resources/dc-agent-config/client.crt"));

        assertThat(certificate).isNotNull();
        assertThat(certificate.getPublicKey()).isNotNull();
        assertThat(certificate.getSubjectDN()).isNotNull();
        assertThat(certificate.getSubjectDN().getName()).isEqualTo("CN=dc-agent-client-01");
        assertThat(certificate.getIssuerDN().getName() ).isEqualTo("CN=dc-agent-test-ca");
    }
}