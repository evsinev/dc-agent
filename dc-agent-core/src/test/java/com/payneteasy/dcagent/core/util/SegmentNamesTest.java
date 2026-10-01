package com.payneteasy.dcagent.core.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SegmentNamesTest {

    @Test
    public void accepts() {
        for (String good : new String[]{"v0.4.1", "1", "current", "release_2-rc.1", "A", "x".repeat(64), "v1..2"}) {
            assertThat(SegmentNames.isValid(good)).as(good).isTrue();
        }
    }

    @Test
    public void refuses() {
        for (String bad : new String[]{null, "", ".", "..", ".hidden", "a/b", "a\\b", "a\0b", "-v1", "_v1", "v 1",
                "v1\n", "v%2E1", "x".repeat(65), "версия"}) {
            assertThat(SegmentNames.isValid(bad)).as(String.valueOf(bad)).isFalse();
        }
    }
}
