package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;

import java.util.ArrayList;
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
    private static final double LIVESET_GROWTH_FACTOR = 1.25; // last live set vs previous

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

        // Rule 2 — worst pause ever seen (catches a spike that has since passed).
        if (gc.getMaxPauseMs() >= WARN_MAX_PAUSE_MS && gc.getLastPauseMs() < WARN_LAST_PAUSE_MS) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "Longest pause so far was %d ms; current pauses are back to normal. Likely a one-off "
                + "(host memory pressure / neighbours), not a standing JVM problem — confirm on the host.",
                gc.getMaxPauseMs())));
        }

        // Rule 3 — recurring long pauses.
        if (gc.getLongPauseCount() >= 3) {
            findings.add(new Finding(Level.CRITICAL, String.format(Locale.ROOT,
                "%d pauses have exceeded %d ms — recurring long stalls, not a one-off. Investigate heap "
                + "sizing and host contention.",
                gc.getLongPauseCount(), gc.getLongPauseThresholdMs())));
        }

        // Rule 4 — live set close to heap max after a collection ⇒ cramped, Full GC / OOM risk.
        double liveFrac = liveSetFraction(heapMax, gc.getLiveSetAfterBytes());
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

        // Rule 5 — live set jumped between the last two collections ⇒ possible leak / accumulation.
        long prev = gc.getPrevLiveSetAfterBytes();
        long last = gc.getLiveSetAfterBytes();
        if (prev > 0 && last > 0 && last > prev * LIVESET_GROWTH_FACTOR) {
            findings.add(new Finding(Level.WARN, String.format(Locale.ROOT,
                "Live set grew from %s to %s between the last two collections — could be normal load or "
                + "the start of a leak. If it keeps climbing, take a heap dump.",
                MetricFormat.bytes(prev), MetricFormat.bytes(last))));
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

    /** Overall verdict from a non-empty finding list: worst level wins, its first message is the summary. */
    private static Verdict summarize(List<Finding> aFindings) {
        Level worst = aFindings.stream().anyMatch(f -> f.level() == Level.CRITICAL) ? Level.CRITICAL : Level.WARN;
        String summary = aFindings.stream()
                .filter(f -> f.level() == worst)
                .map(Finding::message)
                .findFirst()
                .orElse("GC needs attention.");
        return new Verdict(worst, summary, aFindings);
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
