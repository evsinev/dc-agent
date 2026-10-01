package com.payneteasy.dcagent.core.util;

import org.junit.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UnitsTest {

    @Test
    public void sizes() {
        assertThat(Units.parseSize("10")).isEqualTo(10);
        assertThat(Units.parseSize("512k")).isEqualTo(512L * 1024);
        assertThat(Units.parseSize("512kb")).isEqualTo(512L * 1024);
        assertThat(Units.parseSize("50mb")).isEqualTo(50L * 1024 * 1024);
        assertThat(Units.parseSize("50MB")).isEqualTo(50L * 1024 * 1024);
        assertThat(Units.parseSize("50M")).isEqualTo(50L * 1024 * 1024);
        assertThat(Units.parseSize("2g")).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(Units.parseSize(" 7 ".trim())).isEqualTo(7);
    }

    @Test
    public void bad_sizes() {
        for (String bad : new String[]{"5x", "-1", "1.5m", "", "mb", "10 mb", "10b", "10kbb", "999999999999999999g"}) {
            assertThatThrownBy(() -> Units.parseSize(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Units.parseSize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void bad_size_message_does_not_quote_the_value() {
        assertThatThrownBy(() -> Units.parseSize("SECRET")).hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> Units.parseCount("SECRET")).hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> Units.parseDuration("SECRET")).hasMessageNotContaining("SECRET");
    }

    @Test
    public void counts() {
        assertThat(Units.parseCount("500")).isEqualTo(500);
        assertThat(Units.parseCount("10k")).isEqualTo(10_000);
        assertThat(Units.parseCount("10K")).isEqualTo(10_000);
        for (String bad : new String[]{"5x", "-1", "1.5k", "10m", "", "k"}) {
            assertThatThrownBy(() -> Units.parseCount(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void durations() {
        assertThat(Units.parseDuration("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(Units.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Units.parseDuration("1h30m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(Units.parseDuration("PT10S")).isEqualTo(Duration.ofSeconds(10));
        assertThat(Units.parseDuration("1.5s")).isEqualTo(Duration.ofMillis(1500));
        for (String bad : new String[]{"5x", "-1", "1.5m", "", "m"}) {
            assertThatThrownBy(() -> Units.parseDuration(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void duration_default_when_no_text() {
        assertThat(Units.parseDuration(null, "3m")).isEqualTo(Duration.ofMinutes(3));
        assertThat(Units.parseDuration("  ", "3m")).isEqualTo(Duration.ofMinutes(3));
        assertThat(Units.parseDuration("10s", "3m")).isEqualTo(Duration.ofSeconds(10));
    }
}
