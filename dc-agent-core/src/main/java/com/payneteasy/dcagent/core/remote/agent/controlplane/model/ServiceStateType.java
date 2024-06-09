package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

public enum ServiceStateType {

      UP                ( true  )
    , UP_NORMALLY_DOWN  ( true  )
    , UP_PAUSED         ( false )
    , UP_WANT_DOWN      ( true  )
    , DOWN              ( false )
    , DOWN_NORMALLY_UP  ( false )
    , DOWN_WANT_UP      ( false )
    , ERROR             ( false )
    ;

    private final boolean isRunning;

    public boolean isRunning() {
        return isRunning;
    }

    ServiceStateType(boolean isRunning) {
        this.isRunning = isRunning;
    }
}
