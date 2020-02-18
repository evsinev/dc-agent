package com.payneteasy.dcagent.config;

import com.payneteasy.dcagent.exception.ProblemException;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

public class ConfigNotFoundException extends ProblemException {

    public ConfigNotFoundException(String message) {
        super(SC_FORBIDDEN, message);
    }
}
