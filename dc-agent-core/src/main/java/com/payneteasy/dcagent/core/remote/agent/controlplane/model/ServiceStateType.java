package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

public enum ServiceStateType {

      UP                ( true  , true,  true,  false, true  )
    , UP_NORMALLY_DOWN  ( true  , true,  true,  false, true  )
    , UP_PAUSED         ( false , true,  true,  false, true  )
    , UP_WANT_DOWN      ( true  , true,  true,  false, true  )
    , DOWN              ( false , false, false, true , false )
    , DOWN_NORMALLY_UP  ( false , false, false, true , false )
    , DOWN_WANT_UP      ( false , false, false, true , false )
    , ERROR             ( false , false, false, false, false )
    ;

    private final boolean isRunning;
    private final boolean canHangup;
    private final boolean canTerminate;
    private final boolean canUp;
    private final boolean canDown;

    ServiceStateType(boolean isRunning, boolean canHangup, boolean canTerminate, boolean canUp, boolean canDown) {
        this.isRunning    = isRunning;
        this.canHangup    = canHangup;
        this.canTerminate = canTerminate;
        this.canUp        = canUp;
        this.canDown      = canDown;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean canHangup() {
        return canHangup;
    }

    public boolean canTerminate() {
        return canTerminate;
    }

    public boolean canUp() {
        return canUp;
    }

    public boolean canDown() {
        return canDown;
    }
}
