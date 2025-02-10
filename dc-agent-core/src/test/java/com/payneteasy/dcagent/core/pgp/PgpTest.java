package com.payneteasy.dcagent.core.pgp;

import org.pgpainless.sop.SOPImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sop.ByteArrayAndResult;
import sop.ReadyWithResult;
import sop.SOP;
import sop.SigningResult;
import sop.enums.SignAs;

import java.io.IOException;

public class PgpTest {

    private static final Logger LOG = LoggerFactory.getLogger( PgpTest.class );

    public static void main(String[] args) throws IOException {
        SOP sop = new SOPImpl();

        String password = "password-test-1";
        byte[] keyBytes = sop.generateKey()
                .userId("User 1 <user1@test.internal>")
                .signingOnly()
                .withKeyPassword(password)
                .generate()
                .getBytes();

        // extract certificate
        byte[] certificateBytes = sop.extractCert()
                .key(keyBytes)
                .getBytes();

        // sign
        byte[] payload = "hello".getBytes();
        ReadyWithResult<SigningResult> readyWithResult = sop.detachedSign()
                .mode(SignAs.text)
                .key(keyBytes)
                .withKeyPassword(password)
                .data(payload);

        ByteArrayAndResult<SigningResult> byteArrayAndResult = readyWithResult.toByteArrayAndResult();
        LOG.info("signature = \n{}", new String(byteArrayAndResult.getBytes()));

        LOG.info("key  = \n{}", new String(keyBytes));
        LOG.info("cert = \n{}", new String(certificateBytes));
    }
}
