package com.payneteasy.dcagent.core.daemontools;

import org.junit.Test;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SuperviseStatusFileParserTest {

    private static final Base64.Decoder BASE64 = Base64.getDecoder();

    private final SuperviseStatusFileParser parser = new SuperviseStatusFileParser();

    // 40 00 00 00 66 08 07 12 | 14 e9 a9 1c | 55 1a 00 00 | 00 | 75
    // TAI64 2^62 + 0x66080712 (= 10 + 1711802120), 350857500 ns, pid 6741, not paused, want up
    @Test
    public void parse_running() {
        SuperviseStatusFile status = parser.parseStatusFile(BASE64.decode("QAAAAGYIBxIU6akcVRoAAAB1"));

        assertThat(status.getPid()).isEqualTo(6741);
        assertThat(status.getWant()).isEqualTo(WantStateType.WANT_UP);
        assertThat(status.isPaused()).isFalse();
        // the TAI64 label minus 2^62 + 10, the nanoseconds big-endian
        assertThat(Instant.ofEpochMilli(status.getWhen())).isEqualTo(Instant.parse("2024-03-30T12:35:20.350Z"));
    }

    @Test
    public void a_label_past_2038_and_the_pid_bytes_in_order() {
        byte[] bytes = new byte[18];
        long   tai   = (1L << 62) + 10 + 4_102_444_800L; // 2100-01-01T00:00:00Z
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (tai >>> (8 * (7 - i)));
        }
        bytes[12] = 0x04; bytes[13] = 0x03; bytes[14] = 0x02; bytes[15] = 0x01; // pid 0x01020304
        bytes[16] = 1;
        bytes[17] = 'd';

        SuperviseStatusFile status = parser.parseStatusFile(bytes);

        assertThat(Instant.ofEpochMilli(status.getWhen())).isEqualTo(Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(status.getPid()).isEqualTo(0x01020304L);
        assertThat(status.isPaused()).isTrue();
        assertThat(status.getWant()).isEqualTo(WantStateType.WANT_DOWN);
    }

    @Test
    public void refuses_a_status_of_another_length() {
        assertThatThrownBy(() -> parser.parseStatusFile(new byte[20])).hasMessageContaining("18");
    }
}
