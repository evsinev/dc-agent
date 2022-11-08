package com.payneteasy.dcagent.core.config.service;

import com.payneteasy.dcagent.core.exception.ProblemException;

public class ConfigNotFoundException extends ProblemException {

    public ConfigNotFoundException(String message) {
        super(403, message);
    }
}
