package com.payneteasy.dcagent.core.util;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.file.Files.readAllLines;

public class SecureKeys {

    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

    public PrivateKey loadPrivateKeyFile(File aFile) {
        try {
            return createKeyFactory().generatePrivate(
                    new PKCS8EncodedKeySpec(
                            readBytesConvertToDer(aFile)
                    )
            );
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("Cannot load private file " + aFile.getAbsolutePath(), e);
        }
    }

    public PublicKey loadPublicKey(File aFile) {
        try {
            return createKeyFactory().generatePublic(
                    new X509EncodedKeySpec(
                            readBytesConvertToDer(aFile)
                    )
            );
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("Cannot create public key from " + aFile.getAbsolutePath(), e);
        }
    }

    public X509Certificate loadCertificate(File aFile) {
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException("Cannot create X.509 factory", e);
        }

        try(FileInputStream in = new FileInputStream(aFile)) {
            return (X509Certificate) factory.generateCertificate(in);
        } catch (IOException e) {
            throw new IllegalStateException("IO error while reading " + aFile.getAbsolutePath(), e);
        } catch (CertificateException e) {
            throw new IllegalStateException("Bad cert file " + aFile.getAbsolutePath(), e);
        }
    }

    public PublicKey loadPublicKeyFromCertificate(File aFile) {
        return loadCertificate(aFile).getPublicKey();
    }

    @Nonnull
    private static KeyFactory createKeyFactory()  {
        try {
            return KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot create key factory for RSA", e);
        }
    }

    private byte[] readBytesConvertToDer(File aFile) {
        try {
            String base64 = readAllLines(aFile.toPath())
                    .stream()
                    .filter(line -> !line.contains(" KEY-----"))
                    .collect(Collectors.joining());
            return BASE64_DECODER.decode(base64);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + aFile.getAbsolutePath(), e);
        }
    }

    private byte[] readCertBytes(File aFile) throws IOException {

        List<String> allLines  = readAllLines(aFile.toPath());
        List<String> certLines = new ArrayList<>();

        boolean isCertLines = false;

        for (String line : allLines) {
            if(line.trim().equals("-----BEGIN CERTIFICATE-----")) {
                isCertLines = true;
                continue;
            }

            if(line.trim().equals("-----END CERTIFICATE-----")) {
                isCertLines = false;
                continue;
            }

            if(isCertLines) {
                certLines.add(line);
            }

        }

        return BASE64_DECODER.decode(
                String.join("", certLines)
        );
    }

}
