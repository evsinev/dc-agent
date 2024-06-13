package com.payneteasy.dcagent.util;

import com.payneteasy.dcagent.core.modules.jar.ILog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleLogImpl implements ILog {

    private final Logger log;

    public SimpleLogImpl(Class<?> aClass) {
        this.log = LoggerFactory.getLogger(aClass);
    }

    @Override
    public void debug(String aFormat, Object... args) {
        String message = String.format(aFormat, args);
        log.debug("{}", message);
    }
}
