package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

public enum ServiceActionType {
    UP        ( "-u" )  ,
    DOWN      ( "-d" )  ,
    HANGUP    ( "-h" )  ,
    TERMINATE ( "-t" )  ,
    ;

    private final String option;

    ServiceActionType(String option) {
        this.option = option;
    }

    public String getOption() {
        return option;
    }
}
