package com.payneteasy.dcagent.core.util;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

public class RsaSigner {

    public static byte[] calculateRsaSignature(PrivateKey privateKey, byte[] bytes) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(bytes);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to RSA-SHA256 sign the given string with the provided key", e);
        }
    }

}
