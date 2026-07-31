package com.payneteasy.dcagent.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Periodically reads a handful of Linux {@code /proc} signals that the JMX/{@code com.sun} beans do
 * not expose: {@code MemAvailable} (the real "free-ish" figure, unlike {@code MemFree} which excludes
 * page cache), swap-in/swap-out activity (to tell cold parked pages from live thrashing), and how much
 * of THIS JVM is swapped out. Read on a background daemon timer (never on the request hot path); the
 * latest values live in {@code volatile} boxed fields, {@code null} meaning "not collected".
 *
 * <p>Mirrors {@link CpuLoadSampler}: silently disabled when {@code /proc} is unavailable (non-Linux),
 * every read wrapped so any failure leaves the value {@code null} rather than throwing. Parsing is done
 * by pure static helpers so it can be unit-tested against fixtures without a real {@code /proc}.
 */
public class LinuxProcProbe {

    private static final Logger LOG = LoggerFactory.getLogger(LinuxProcProbe.class);

    private static final Path MEMINFO     = Path.of("/proc/meminfo");
    private static final Path VMSTAT      = Path.of("/proc/vmstat");
    private static final Path SELF_STATUS = Path.of("/proc/self/status");

    // Held for the app lifetime; runs on a daemon thread, so it needs no explicit shutdown.
    private ScheduledExecutorService executor;

    // Latest readings, published to request threads. null = not collected / unavailable.
    private volatile Long   memAvailableBytes;
    private volatile Long   swapCachedBytes;
    private volatile Long   processSwapBytes;
    private volatile Double swapInPagesPerSec;
    private volatile Double swapOutPagesPerSec;

    // Rate state — touched only by the single sampler thread, so plain fields are enough.
    private Long prevPswpIn;
    private Long prevPswpOut;
    private Long prevSampleEpochMs;

    /** Start the daemon probe. No-op when {@code /proc/meminfo} is absent (non-Linux host). */
    public void start(long aIntervalMs) {
        if (!Files.exists(MEMINFO)) {
            LOG.info("/proc/meminfo not present; Linux memory/swap signals will report null");
            return;
        }
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "linux-proc-probe");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(threads);
        executor.scheduleWithFixedDelay(this::sample, 0, Math.max(500, aIntervalMs), MILLISECONDS);
    }

    private void sample() {
        readMeminfo();
        readSelfStatus();
        readVmstat();
    }

    private void readMeminfo() {
        try {
            List<String> lines = Files.readAllLines(MEMINFO);
            memAvailableBytes = parseKbLineBytes(lines, "MemAvailable");
            swapCachedBytes   = parseKbLineBytes(lines, "SwapCached");
        } catch (Exception e) {
            LOG.debug("Cannot read /proc/meminfo", e);
        }
    }

    private void readSelfStatus() {
        try {
            processSwapBytes = parseKbLineBytes(Files.readAllLines(SELF_STATUS), "VmSwap");
        } catch (Exception e) {
            LOG.debug("Cannot read /proc/self/status", e);
        }
    }

    private void readVmstat() {
        try {
            List<String> lines  = Files.readAllLines(VMSTAT);
            Long         curIn  = parseCounter(lines, "pswpin");
            Long         curOut = parseCounter(lines, "pswpout");
            long         now    = System.currentTimeMillis();

            if (prevSampleEpochMs != null) {
                double dtSec = (now - prevSampleEpochMs) / 1000.0;
                if (dtSec > 0) {
                    swapInPagesPerSec  = rate(prevPswpIn, curIn, dtSec);
                    swapOutPagesPerSec = rate(prevPswpOut, curOut, dtSec);
                }
            }
            prevPswpIn        = curIn;
            prevPswpOut       = curOut;
            prevSampleEpochMs = now;
        } catch (Exception e) {
            LOG.debug("Cannot read /proc/vmstat", e);
        }
    }

    /** Per-second rate between two since-boot counter readings; null on a missing reading (first sample). */
    static Double rate(Long aPrev, Long aCur, double aDtSec) {
        if (aPrev == null || aCur == null) {
            return null;
        }
        return Math.max(0, aCur - aPrev) / aDtSec;
    }

    /**
     * Parse a {@code "Key:  <number> kB"} line (as in /proc/meminfo and /proc/self/status) and return
     * the value in bytes, or {@code null} when the key is absent or unparseable.
     */
    static Long parseKbLineBytes(List<String> aLines, String aKey) {
        String prefix = aKey + ":";
        for (String line : aLines) {
            if (line.startsWith(prefix)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Long.parseLong(parts[1]) * 1024L;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Parse a {@code "<key> <number>"} counter line (as in /proc/vmstat) and return the raw count, or
     * {@code null} when the key is absent or unparseable.
     */
    static Long parseCounter(List<String> aLines, String aKey) {
        for (String line : aLines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].equals(aKey)) {
                try {
                    return Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public Long getMemAvailableBytes() {
        return memAvailableBytes;
    }

    public Long getSwapCachedBytes() {
        return swapCachedBytes;
    }

    public Long getProcessSwapBytes() {
        return processSwapBytes;
    }

    public Double getSwapInPagesPerSec() {
        return swapInPagesPerSec;
    }

    public Double getSwapOutPagesPerSec() {
        return swapOutPagesPerSec;
    }
}
