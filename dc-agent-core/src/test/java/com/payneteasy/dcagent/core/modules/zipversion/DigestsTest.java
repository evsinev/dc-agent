package com.payneteasy.dcagent.core.modules.zipversion;

import org.junit.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

public class DigestsTest {

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test
    public void the_file_is_64_lowercase_hex_and_a_newline() {
        assertThat(new String(Digests.fileContents(DIGEST), US_ASCII)).isEqualTo(DIGEST + "\n");
        assertThat(Digests.parseFile(Digests.fileContents(DIGEST))).isEqualTo(DIGEST);
    }

    @Test
    public void parsing_strips_one_trailing_newline_only() {
        assertThat(Digests.parseFile(DIGEST.getBytes(US_ASCII))).isEqualTo(DIGEST);
        for (String bad : new String[]{DIGEST + "\n\n", DIGEST + "\r\n", " " + DIGEST, DIGEST.toUpperCase(), DIGEST.substring(1), "", "\n"}) {
            assertThat(Digests.parseFile(bad.getBytes(US_ASCII))).as(bad).isNull();
        }
    }
}
