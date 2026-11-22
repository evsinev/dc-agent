package com.payneteasy.dcagent.core.daemontools;

/** The fields of daemontools' {@code supervise/status}: a TAI64N label, then little-endian integers. */
public class SuperviseStatusBuffer {

    /** TAI64 label of 1970-01-01 00:00:10 TAI, as daemontools' {@code tai_now()} counts: 2^62 + 10. */
    private static final long TAI64_UNIX_EPOCH = (1L << 62) + 10;

    private final byte[] buffer;

    public SuperviseStatusBuffer(byte[] buffer) {
        this.buffer = buffer.clone();
    }

    public int uint8(int aPosition) {
        return 0xff & buffer[aPosition];
    }

    /**
     * A TAI64N label (https://cr.yp.to/libtai/tai64.html) as Unix milliseconds: 8 bytes of seconds,
     * then 4 bytes of nanoseconds, both big-endian.
     */
    public long timestamp(int aPosition) {
        long seconds     = bigEndian(aPosition, 8) - TAI64_UNIX_EPOCH;
        long nanoseconds = bigEndian(aPosition + 8, 4);
        return seconds * 1000L + nanoseconds / 1_000_000L;
    }

    /** A little-endian unsigned 32-bit integer (the pid). */
    public long uint32(int aPosition) {
        long value = 0;
        for (int i = 3; i >= 0; i--) {
            value = (value << 8) | uint8(aPosition + i);
        }
        return value;
    }

    private long bigEndian(int aPosition, int aLength) {
        long value = 0;
        for (int i = 0; i < aLength; i++) {
            value = (value << 8) | uint8(aPosition + i);
        }
        return value;
    }
}
