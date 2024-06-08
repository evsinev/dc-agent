package com.payneteasy.dcagent.admin.service.daemontools.impl;

public class SuperviseStatusBuffer {
    private final byte[] buffer;

    public SuperviseStatusBuffer(byte[] buffer) {
        this.buffer = buffer;
    }

    public int uint8(int aPosition) {
        return 0xff & buffer[aPosition];
    }

    public long timestamp(int aPosition) {
        // https://cr.yp.to/libtai/tai64.html
        // First 8 bytes (16 hex chars) of TAI64N are seconds. TAI64's unix epoch is at 2^62
        // Compensate for leap seconds to convert TAI to UTC
        long secondsSinceEpoch = tai_unpack(aPosition) & ((1L << 62) - 1) - 10;

        // last 4 bytes (8 hex chars) of TAI64N are subsecond value in nanoseconds.
        long nanoseconds = uint32(aPosition + 8);

        return secondsSinceEpoch * 1000L + (nanoseconds / 1_000_000L);
    }

    public int tai_unpack(int aPosition) {
        
        // see https://github.com/daemontools/daemontools/blob/1e71548fe842c45cc3152caa0d22d9bc09b5527e/src/tai_unpack.c#L9
        int x;
        x = 0xff & buffer[aPosition];
        x <<= 8; x += 0xff & buffer[aPosition + 1];
        x <<= 8; x += 0xff & buffer[aPosition + 2];
        x <<= 8; x += 0xff & buffer[aPosition + 3];
        x <<= 8; x += 0xff & buffer[aPosition + 4];
        x <<= 8; x += 0xff & buffer[aPosition + 5];
        x <<= 8; x += 0xff & buffer[aPosition + 6];
        x <<= 8; x += 0xff & buffer[aPosition + 7];
        return x;
    }

    public long uint32(int aPosition) {
        long pid;

        pid = 0xff & buffer[aPosition + 3];
        pid <<= 8; pid += 0xff & buffer[aPosition + 2];
        pid <<= 8; pid += 0xff & buffer[aPosition + 1];
        pid <<= 8; pid += 0xff & buffer[aPosition];

        return pid;
    }
}
