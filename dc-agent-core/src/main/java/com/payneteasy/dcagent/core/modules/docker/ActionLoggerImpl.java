package com.payneteasy.dcagent.core.modules.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

public class ActionLoggerImpl implements IActionLogger {

    private static final Logger LOG = LoggerFactory.getLogger( ActionLoggerImpl.class );

    private final StringBuilder sb = new StringBuilder();

    @Override
    public void info(String aPattern, Object... args) {
        LOG.info(aPattern, args);

        sb.append(MessageFormatter.arrayFormat(aPattern, args).getMessage());
        sb.append("\n");
    }

    public String buildText() {
        return sb.toString();
    }
}
