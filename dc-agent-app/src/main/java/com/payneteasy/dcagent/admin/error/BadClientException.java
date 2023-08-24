package com.payneteasy.dcagent.admin.error;

public class BadClientException extends IllegalStateException {

    public BadClientException() {
    }

    public BadClientException(String s) {
        super(s);
    }
}
