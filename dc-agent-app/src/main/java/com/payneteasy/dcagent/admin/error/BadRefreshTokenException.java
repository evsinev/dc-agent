package com.payneteasy.dcagent.admin.error;

public class BadRefreshTokenException extends IllegalStateException {

    public BadRefreshTokenException(String s) {
        super(s);
    }
}
