package com.payneteasy.dcagent.core.exception;

public class ProblemException extends RuntimeException {

    private final int httpCode;

    public ProblemException(int aHttpCode, String message) {
        super(message);
        httpCode = aHttpCode;
    }

    public ProblemException(int aHttpCode, String message, Throwable cause) {
        super(message, cause);
        httpCode = aHttpCode;
    }

    public int getHttpCode() {
        return httpCode;
    }
}
