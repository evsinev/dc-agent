package com.payneteasy.dcagent.core.modules.docker;

import org.slf4j.helpers.MessageFormatter;

public class ActionLoggerImpl implements IActionLogger {

    private final StringBuilder sb = new StringBuilder();

    @Override
    public void info(String aPattern, Object... args) {
        sb.append(MessageFormatter.arrayFormat(aPattern, args).getMessage());
        sb.append("\n");
    }

    public String buildText() {
        return sb.toString();
    }
}
