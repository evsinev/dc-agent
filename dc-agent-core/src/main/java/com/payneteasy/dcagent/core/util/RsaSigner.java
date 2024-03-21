package com.payneteasy.dcagent.core.util;

import java.security.*;

import static com.payneteasy.dcagent.core.exception.DcProblem.*;
import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;

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

    public static boolean verifySignature(PublicKey aPublicKey, byte[] aSignature, byte[] aBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(aPublicKey);
            signature.update(aBytes);
            return signature.verify(aSignature);
        } catch (NoSuchAlgorithmException e) {
            throw problem(NO_SHA256_WITH_RSA).exception(e);
        } catch (InvalidKeyException e) {
            throw problem(INVALID_PUBLIC_KEY).exception(e);
        } catch (SignatureException e) {
            throw problem(CANNOT_CALC_SIGNATURE).exception(e);
        }

    }
}
