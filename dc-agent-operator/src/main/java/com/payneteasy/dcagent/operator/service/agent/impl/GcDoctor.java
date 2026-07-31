package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TLiveSetSample;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns raw GC numbers into a plain-English verdict, without an LLM. This is a fixed set of narrow
 * rules: each rule is a boolean predicate over the readings, and each carries a pre-written message.
 * A rule firing means "this pattern is present", not "the model understood the situation" — the
 * text is a canned explanation attached to the condition, so the narrower the condition the more
 * specific its message can honestly be.
 *
 * <p>What it can see: pause magnitude (last/avg/max), long-pause count, and the live-set trend
 * (heap used after the last two GCs). What it cannot see: the user/sys/real CPU split from the
 * unified GC log — the JMX notification does not carry it — so the "wall-clock &gt; cpu-time ⇒ the
 * host, not the JVM" inference is not available here and is not claimed.
 *
 * <p>Severity ordering: CRITICAL &gt; WARN &gt; OK. The overall level is the worst finding.
 */
final class GcDoctor {

    private GcDoctor() {
    }

    enum Level { OK, WARN, CRITICAL }

    record Finding(Level level, String message) {
    }

    record Verdict(Level level, String summary, List<Finding> findings) {
    }

    // Tunables. Kept as constants here; promote to operator config if per-fleet tuning is needed.
    private static final long   WARN_LAST_PAUSE_MS    = 100;
    private static final long   CRIT_LAST_PAUSE_MS    = 500;
    private static final long   WARN_MAX_PAUSE_MS     = 200;
    private static final double CRIT_HEAP_AFTER_FRAC  = 0.80; // live set this close to max ⇒ trouble
    private static final double WARN_HEAP_AFTER_FRAC  = 0.60;
    private static final int    MIN_COLLECTIONS_FOR_LIVEFRAC = 3;

    private static final long   IDLE_INTERVAL_MS      = 10 * 60 * 1000L; // > 10 min between collections …
    private static final long   IDLE_ALLOC_RATE_BPS   = 100 * 1024;      // … and < 100 KiB/s ⇒ idle
    private static final double SWAP_OUT_WARN_PPS     = 50;              // pages/s swapped out (sustained)
    private static final long   PROC_SWAP_WARN_BYTES  = 16L * 1024 * 1024;
    private static final double MEM_AVAIL_WARN_FRAC   = 0.05;
    private static final int    OLDGEN_MIN_SAMPLES    = 3;
    private static final long   OLDGEN_MIN_WINDOW_MS  = 60 * 60 * 1000L; // 1 hour
    private static final double OLDGEN_GROWTH_FRAC_PH = 0.05;            // 5% of old-gen max per hour

    /**
     * Produce a verdict from the system info. {@code aInfo} carries heap max (for the live-set
     * fraction) and the rich {@link TGcInfo}. Returns an "insufficient data" OK verdict when GC
     * stats are not yet available.
     */
    static Verdict diagnose(TSystemInfo aInfo) {
        TGcInfo gc = aInfo == null ? null : aInfo.getGc();
        if (gc == null || gc.getCollectionCount() <= 0) {
            return new Verdict(Level.OK, "No GC activity recorded yet — nothing to report.", List.of());
        }

        List<Finding> findings = new ArrayList<>();
        long heapMax = aInfo.getHeapMaxBytes();

        // Idle — added first so it leads the summary. A JVM that barely allocates says little about how
        // it behaves under load; flag that before any GC-derived reading, which would otherwise mislead.
        Long allocRate = allocationRateBytesPerSec(gc, aInfo.getUptimeMs());
        Double avgInterval = gc.getAvgGcIntervalMs();
        if (avgInterval != null && avgInterval > IDLE_INTERVAL_MS && allocRate != null && allocRate < IDLE_ALLOC_RATE_BPS) {
            findings.add(new Finding(Level.OK, String.format(Locale.ROOT,
                "Service is nearly idle: allocating ~%s/s, ~%s between collections — these GC numbers say "
                + "little about behaviour under load. If it should be serving traffic, check the traffic is "
                + "actually reaching it.",
                MetricFormat.bytes(allocRate), intervalText(avgInterval))));
        }

        // Rule 1 — last pause magnitude.
        if (gc.getLastPauseMs() >= CRIT_LAST_PAUSE_MS) {
            findings.add(new Finding(Level.CRITICAL, String.format(Locale.ROOT,
                "Last GC pause was %d ms (cause: %s) — a stop-the-world stall this long will be felt by callers.",
                gc.getLastPauseMs(), safeCause(gc))));
        } else if (gc.getLastPauseMs() >= WARN_LAST_PAUSE_MS) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "Last GC pause was %d ms (cause: %s) — above the comfortable range; watch for repeats.",
                gc.getLastPauseMs(), safeCause(gc))));
        }

        // Rule 2 — worst recent pause (a spike that has since passed). Prefer the last-hour figure so a
        // single warm-up spike does not brand the agent forever; fall back to all-time when unavailable.
        long recentMaxPause = gc.getMaxPauseRecentMs() != null ? gc.getMaxPauseRecentMs() : gc.getMaxPauseMs();
        if (recentMaxPause >= WARN_MAX_PAUSE_MS && gc.getLastPauseMs() < WARN_LAST_PAUSE_MS) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "Longest recent pause was %d ms; current pauses are back to normal. Likely a one-off "
                + "(host memory pressure / neighbours), not a standing JVM problem — confirm on the host.",
                recentMaxPause)));
        }

        // Rule 3 — recurring long pauses.
        if (gc.getLongPauseCount() >= 3) {
            findings.add(new Finding(Level.CRITICAL, String.format(Locale.ROOT,
                "%d pauses have exceeded %d ms — recurring long stalls, not a one-off. Investigate heap "
                + "sizing and host contention.",
                gc.getLongPauseCount(), gc.getLongPauseThresholdMs())));
        }

        // Rule 4 — live set close to heap max after a collection ⇒ cramped, Full GC / OOM risk. Needs a
        // few collections first: one early reading is not yet a trend.
        double liveFrac = gc.getCollectionCount() >= MIN_COLLECTIONS_FOR_LIVEFRAC
                ? liveSetFraction(heapMax, gc.getLiveSetAfterBytes())
                : -1;
        if (liveFrac >= CRIT_HEAP_AFTER_FRAC) {
            findings.add(new Finding(Level.CRITICAL, String.format(Locale.ROOT,
                "After the last GC the heap is still %.0f%% full (%s of %s) — the live set is near the "
                + "ceiling; Full GC or OOM is close. Raise -Xmx or find what is retained.",
                liveFrac * 100, MetricFormat.bytes(gc.getLiveSetAfterBytes()), MetricFormat.bytes(heapMax))));
        } else if (liveFrac >= WARN_HEAP_AFTER_FRAC) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "After the last GC the heap is %.0f%% full — getting tight, keep an eye on it.",
                liveFrac * 100)));
        }

        // Rule 5 — old gen after GC trending up over a real time window ⇒ possible leak. Two adjacent
        // collections are noise; require several samples spanning at least an hour before saying anything.
        Finding oldGenGrowth = oldGenGrowthFinding(gc);
        if (oldGenGrowth != null) {
            findings.add(oldGenGrowth);
        }

        // Host — signals from outside the JVM that still move GC behaviour. Each guard skips a null
        // reading (older agent / non-Linux); a null field is never treated as zero.
        Double swapOut = aInfo.getSwapOutPagesPerSec();
        if (swapOut != null && swapOut > SWAP_OUT_WARN_PPS) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "Host is swapping out (%.0f pages/s) — GC pauses can grow by an order of magnitude with no "
                + "change in the application. Check host memory pressure.", swapOut)));
        }

        Long procSwap = aInfo.getProcessSwapBytes();
        if (procSwap != null && procSwap > PROC_SWAP_WARN_BYTES) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "%s of this JVM is swapped out — marking during the next full GC will fault those pages "
                + "back in as major page faults. Check host memory pressure.", MetricFormat.bytes(procSwap))));
        }

        Long memAvail  = aInfo.getMemAvailableBytes();
        long physTotal = aInfo.getPhysicalTotalBytes();
        if (memAvail != null && physTotal > 0 && (double) memAvail / physTotal < MEM_AVAIL_WARN_FRAC) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "Only %s of %s is available (MemAvailable) — under 5%% headroom on the host; a spike can "
                + "push it into swap or the OOM killer.", MetricFormat.bytes(memAvail), MetricFormat.bytes(physTotal))));
        }

        if (findings.isEmpty()) {
            return new Verdict(Level.OK, String.format(Locale.ROOT,
                "GC healthy: %d collections, avg %s, max %s, live set %s. No leak or pressure signs.",
                gc.getCollectionCount(), ms(gc.getAvgPauseMs()), msLong(gc.getMaxPauseMs()),
                MetricFormat.bytes(gc.getLiveSetAfterBytes())),
                findings);
        }

        return summarize(findings);
    }

    /** Live set as a fraction of heap max, or -1 when either figure is unavailable. */
    private static double liveSetFraction(long aHeapMax, long aLiveSetAfterBytes) {
        return aHeapMax > 0 && aLiveSetAfterBytes >= 0 ? (double) aLiveSetAfterBytes / aHeapMax : -1;
    }

    /**
     * WARN finding when old gen after GC is climbing over a real window (≥{@value #OLDGEN_MIN_SAMPLES}
     * samples, ≥1 h, ≥5%/h of old-gen max), else null. Guard clauses keep the nesting flat.
     */
    private static Finding oldGenGrowthFinding(TGcInfo aGc) {
        List<TLiveSetSample> samples = aGc.getOldGenSamples();
        Long oldMax = aGc.getOldGenMaxBytes();
        if (samples == null || samples.size() < OLDGEN_MIN_SAMPLES || oldMax == null || oldMax <= 0) {
            return null;
        }
        TLiveSetSample first  = samples.get(0);
        TLiveSetSample latest = samples.get(samples.size() - 1);
        long windowMs = latest.getEpochMs() - first.getEpochMs();
        if (windowMs < OLDGEN_MIN_WINDOW_MS) {
            return null;
        }
        double hours        = windowMs / 3_600_000.0;
        double bytesPerHour = (latest.getUsedBytes() - first.getUsedBytes()) / hours;
        double fracPerHour  = bytesPerHour / oldMax;
        if (fracPerHour < OLDGEN_GROWTH_FRAC_PH) {
            return null;
        }
        return new Finding(Level.WARN, String.format(Locale.ROOT,
            "Old gen after GC grew ~%s/hour (%.1f%% of old-gen max) over %.1f h — could be normal load or "
            + "the start of a leak. If it keeps climbing, take a heap dump.",
            MetricFormat.bytes(Math.round(bytesPerHour)), fracPerHour * 100, hours));
    }

    /**
     * Overall verdict from a non-empty finding list: the worst level wins (a lone OK finding — e.g. the
     * idle notice — keeps the verdict OK, it is not promoted to WARN), and the first message at that
     * level is the summary. Findings are already ordered with idle first, so the idle notice leads when
     * nothing worse is present.
     */
    private static Verdict summarize(List<Finding> aFindings) {
        Level worst = aFindings.stream()
                .map(Finding::level)
                .max(Comparator.naturalOrder())
                .orElse(Level.OK);
        String summary = aFindings.stream()
                .filter(f -> f.level() == worst)
                .map(Finding::message)
                .findFirst()
                .orElse("GC needs attention.");
        return new Verdict(worst, summary, aFindings);
    }

    /** Allocation rate in bytes/sec = eden allocated / uptime; null when either input is missing. */
    private static Long allocationRateBytesPerSec(TGcInfo aGc, Long aUptimeMs) {
        if (aGc.getAllocatedBytesTotal() == null || aUptimeMs == null || aUptimeMs <= 0) {
            return null;
        }
        return Math.round(aGc.getAllocatedBytesTotal() * 1000.0 / aUptimeMs);
    }

    /** Coarse "1h 5m" / "57m 13s" rendering of a millisecond interval, for the idle message. */
    private static String intervalText(double aMs) {
        long totalSec = Math.round(aMs / 1000);
        long hours    = totalSec / 3600;
        long minutes  = (totalSec % 3600) / 60;
        long seconds  = totalSec % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static String safeCause(TGcInfo gc) {
        return gc.getLastCause() == null ? "unknown" : gc.getLastCause();
    }

    private static String ms(double aMs) {
        return aMs < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f ms", aMs);
    }

    private static String msLong(long aMs) {
        return aMs < 0 ? "n/a" : aMs + " ms";
    }
}
