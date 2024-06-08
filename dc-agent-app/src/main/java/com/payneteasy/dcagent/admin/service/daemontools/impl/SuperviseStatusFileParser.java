package com.payneteasy.dcagent.admin.service.daemontools.impl;

import com.payneteasy.dcagent.admin.service.daemontools.model.SuperviseStatusFile;
import com.payneteasy.dcagent.admin.service.daemontools.model.WantStateType;

public class SuperviseStatusFileParser {

    // 00      00 when seconds
    // 01      40 when seconds
    // 02      00 when seconds
    // 03      00 when seconds
    // 04      08 when seconds
    // 05      66 when seconds
    // 06      12 when seconds
    // 07      07 when seconds
    // 08      e9 when ns
    // 09      14 when ns
    // 0a  10  1c when ns
    // 0b  11  a9 when ns
    // 0c  12  1a pid
    // 0d  13  55 pid
    // 0e  14  00 pid
    // 0f  15  00 pid
    // 10  16  75 paused
    // 11  17  00 want
    public SuperviseStatusFile parseStatusFile(byte[] aStatus) {
        if (aStatus.length != 18) {
            throw new IllegalStateException("Bad format. Length of status is " + aStatus.length + " but should be 18");
        }

        SuperviseStatusBuffer buffer = new SuperviseStatusBuffer(aStatus);

        long    when   = buffer.timestamp(0);
        long    pid    = buffer.uint32(12);
        boolean paused = buffer.uint8(16) != 0;
        char    want   = (char) buffer.uint8(17);

        return SuperviseStatusFile.builder()
                .when   ( when            )
                .pid    ( pid             )
                .paused ( paused          )
                .want   ( parseWant(want) )
                .build();

    }

    private WantStateType parseWant(char want) {
        switch (want) {
            case 'u': return WantStateType.WANT_UP;
            case 'd': return WantStateType.WANT_DOWN;
            case   0: return WantStateType.EMPTY;
            default:
                throw new IllegalStateException("Unknown want type " + want);
        }
    }
}
