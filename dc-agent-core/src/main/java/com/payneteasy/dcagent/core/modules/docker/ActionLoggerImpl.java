package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

public class ActionLoggerImpl implements IActionLogger {

    private static final Logger LOG = LoggerFactory.getLogger( ActionLoggerImpl.class );

    private final StringBuilder sb = new StringBuilder();

    @Override
    public void info(String aPattern, Object... args) {
        String msg = MessageFormatter.arrayFormat(aPattern, args).getMessage();
        LOG.info("{}", Strings.forLog(msg));

        sb.append(msg);
        sb.append("\n");
    }

    public String buildText() {
        return sb.toString();
    }
}
