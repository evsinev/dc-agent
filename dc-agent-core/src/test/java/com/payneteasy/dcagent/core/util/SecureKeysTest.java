package com.payneteasy.dcagent.core.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import static com.google.common.truth.Truth.assertThat;


public class SecureKeysTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

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

    @Test
    public void load_public_key_from_x509_encoded_pem() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        File pem = folder.newFile("pub.pem");
        String body = Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.write(pem.toPath(), ("-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n")
                .getBytes(StandardCharsets.UTF_8));

        PublicKey publicKey = secureKeys.loadPublicKey(pem);

        assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
        assertThat(publicKey.getFormat()).isEqualTo("X.509");
    }

    @Test(expected = IllegalStateException.class)
    public void load_certificate_of_missing_file_throws() {
        secureKeys.loadCertificate(new File("does/not/exist.crt"));
    }
}