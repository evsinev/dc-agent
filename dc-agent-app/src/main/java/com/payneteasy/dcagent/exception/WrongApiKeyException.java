package com.payneteasy.dcagent.exception;

import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

public class WrongApiKeyException extends ProblemException {

    public WrongApiKeyException(String message) {
        super(SC_UNAUTHORIZED, message);
    }
}
