package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;

/**
 * A config field with a bad value: {@code config <name>: field <field>: <reason>}. Never carries
 * the value or a cause — a value may be a secret (a {@code reloadHeaders} token) and JDK messages
 * quote it. Thrown after the call is authorized, so naming the field reveals nothing new.
 */
public class ConfigValueException extends ProblemException {

    private final String field;
    private final String reason;

    public ConfigValueException(String aConfigName, String aField, String aReason) {
        super(500, "config " + aConfigName + ": field " + aField + ": " + aReason);
        field  = aField;
        reason = aReason;
    }

    public String getField() {
        return field;
    }

    public String getReason() {
        return reason;
    }
}
