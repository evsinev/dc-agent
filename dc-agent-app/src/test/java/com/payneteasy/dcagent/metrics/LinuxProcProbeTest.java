package com.payneteasy.dcagent.metrics;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser + rate-math tests for {@link LinuxProcProbe}, exercised against fixed {@code /proc} fixtures
 * so they run identically on any host (including non-Linux CI). The probe's file reads and scheduling
 * are not tested here — only the pure parsing/rate helpers, which is where all the logic lives.
 */
public class LinuxProcProbeTest {

    private static final Path MEMINFO = Path.of("src/test/resources/proc/meminfo");
    private static final Path VMSTAT  = Path.of("src/test/resources/proc/vmstat");
    private static final Path STATUS  = Path.of("src/test/resources/proc/self-status");

    private static List<String> lines(Path aPath) throws IOException {
        return Files.readAllLines(aPath);
    }

    @Test
    public void parsesMemAvailableAndSwapCachedAsBytes() throws IOException {
        List<String> meminfo = lines(MEMINFO);
        assertThat(LinuxProcProbe.parseKbLineBytes(meminfo, "MemAvailable")).isEqualTo(5_988_352L * 1024);
        assertThat(LinuxProcProbe.parseKbLineBytes(meminfo, "SwapCached")).isEqualTo(12_345L * 1024);
        // Prefix-exact: "MemFree" must not match "MemAvailable" or vice-versa.
        assertThat(LinuxProcProbe.parseKbLineBytes(meminfo, "MemFree")).isEqualTo(383_184L * 1024);
    }

    @Test
    public void parsesVmSwapFromSelfStatus() throws IOException {
        assertThat(LinuxProcProbe.parseKbLineBytes(lines(STATUS), "VmSwap")).isEqualTo(4_096L * 1024);
    }

    @Test
    public void parsesVmstatCounters() throws IOException {
        List<String> vmstat = lines(VMSTAT);
        assertThat(LinuxProcProbe.parseCounter(vmstat, "pswpin")).isEqualTo(42L);
        assertThat(LinuxProcProbe.parseCounter(vmstat, "pswpout")).isEqualTo(1024L);
    }

    @Test
    public void returnsNullForMissingKeys() throws IOException {
        assertThat(LinuxProcProbe.parseKbLineBytes(lines(MEMINFO), "NoSuchKey")).isNull();
        assertThat(LinuxProcProbe.parseCounter(lines(VMSTAT), "no_such_counter")).isNull();
    }

    @Test
    public void firstSampleHasNoRate() {
        // prev == null models the very first vmstat read: no rate can be computed yet.
        assertThat(LinuxProcProbe.rate(null, 100L, 2.0)).isNull();
    }

    @Test
    public void rateIsDeltaOverInterval() {
        // 200 pages over 2 seconds = 100 pages/s.
        assertThat(LinuxProcProbe.rate(100L, 300L, 2.0)).isEqualTo(100.0);
        assertThat(LinuxProcProbe.rate(300L, 300L, 2.0)).isEqualTo(0.0);
    }

    @Test
    public void counterResetClampsToZero() {
        // A since-boot counter can only be reset by a reboot; never report a negative rate.
        assertThat(LinuxProcProbe.rate(500L, 100L, 2.0)).isEqualTo(0.0);
    }
}
