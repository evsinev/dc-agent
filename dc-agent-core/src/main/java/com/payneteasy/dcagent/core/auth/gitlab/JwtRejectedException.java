package com.payneteasy.dcagent.core.auth.gitlab;

/** Why a token (or an issuer file) is not accepted — for the agent log only, never a token value. */
public class JwtRejectedException extends Exception {

    public JwtRejectedException(String aReason) {
        super(aReason);
    }
}
