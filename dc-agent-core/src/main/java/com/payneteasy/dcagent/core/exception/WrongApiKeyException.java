package com.payneteasy.dcagent.core.exception;

public class WrongApiKeyException extends ProblemException {

    public WrongApiKeyException(String message) {
        super(401, message);
    }
}
