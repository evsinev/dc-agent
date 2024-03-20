package com.payneteasy.dcagent.core.job.create;

import com.fasterxml.uuid.Generators;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static java.lang.System.currentTimeMillis;
import static java.nio.ByteBuffer.allocate;
import static java.util.UUID.randomUUID;

public class JobIds {

    private static class Holder {
        private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static String createJobId() {
        UUID       uuid   = randomUUID();

        ByteBuffer buffer = allocate(24);
        buffer.putLong(currentTimeMillis());
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        buffer.rewind();

        return ENCODER.encodeToString(buffer.array());
    }

    public static String createJobIdTime() {
        UUID       uuid   = Generators.timeBasedEpochRandomGenerator(Holder.SECURE_RANDOM).generate();

        ByteBuffer buffer = allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        buffer.rewind();

        return ENCODER.encodeToString(buffer.array());
    }
}
