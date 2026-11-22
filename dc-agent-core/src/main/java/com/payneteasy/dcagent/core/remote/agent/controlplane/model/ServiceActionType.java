package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

public enum ServiceActionType {
    UP        ( "-u", 'u' )  ,
    DOWN      ( "-d", 'd' )  ,
    HANGUP    ( "-h", 'h' )  ,
    TERMINATE ( "-t", 't' )  ,
    ;

    private final String option;
    private final char   command;

    ServiceActionType(String option, char command) {
        this.option  = option;
        this.command = command;
    }

    /** The svc option, as the operator shows it. */
    public String getOption() {
        return option;
    }

    /** The byte written to {@code supervise/control}. */
    public char getCommand() {
        return command;
    }
}
