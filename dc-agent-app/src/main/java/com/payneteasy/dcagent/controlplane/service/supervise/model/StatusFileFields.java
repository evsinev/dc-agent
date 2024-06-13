package com.payneteasy.dcagent.controlplane.service.supervise.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class StatusFileFields {

    long when;      // 0  - 11  see http://cr.yp.to/libtai/tai64.html#tai64n
    long pid;       // 12 - 15
    char paused;    // 16       status[16] = (pid ? flagpaused : 0);
    char want;      // 17       status[17] = (flagwant ? (flagwantup ? 'u' : 'd') : 0);

    WantStateType    wantState;
    boolean          isPaused;
    boolean          normallyUp;

}
