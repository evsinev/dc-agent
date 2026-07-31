package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TLiveSetSample;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Each test crafts a {@link TGcInfo}/{@link TSystemInfo} that isolates a single rule so we can assert
 * both the level and the honest wording. The heap max is fixed at 1 GB so the live-set fractions are
 * easy to reason about. (Payload wording lives in {@link GcLlmPayloadBuilderTest}.)
 */
public class GcDoctorTest {

    private static final long HEAP_MAX = 1_000_000_000L; // ~1 GB, keeps the live-set math obvious
    private static final long HOUR_MS  = 3_600_000L;

    /** A quiet, healthy collector: small pauses, tiny live set, no growth. */
    private static TGcInfo.TGcInfoBuilder healthyGc() {
        return TGcInfo.builder()
                .collectionCount(10)
                .totalPauseMs(200)
                .avgPauseMs(20)
                .maxPauseMs(30)
                .lastPauseMs(20)
                .lastGcEpochMs(1)
                .longPauseCount(0)
                .longPauseThresholdMs(200)
                .liveSetAfterBytes(100_000_000L)   // 10 % of heap
                .prevLiveSetAfterBytes(100_000_000L)
                .lastCause("G1 Evacuation Pause")
                .lastAction("end of minor GC");
    }

    private static TSystemInfo sys(TGcInfo gc) {
        return TSystemInfo.builder().heapMaxBytes(HEAP_MAX).gc(gc).build();
    }

    @Test
    public void nullGc_isOk_noActivity() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(TSystemInfo.builder().heapMaxBytes(HEAP_MAX).build());
        assertEquals(GcDoctor.Level.OK, verdict.level());
        assertTrue(verdict.findings().isEmpty());
        assertTrue(verdict.summary().contains("No GC activity"));
    }

    @Test
    public void zeroCollections_isOk_noActivity() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc().collectionCount(0).build()));
        assertEquals(GcDoctor.Level.OK, verdict.level());
    }

    @Test
    public void healthy_isOk_withSummary() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc().build()));
        assertEquals(GcDoctor.Level.OK, verdict.level());
        assertTrue(verdict.findings().isEmpty());
        assertTrue(verdict.summary().contains("GC healthy"));
    }

    @Test
    public void lastPauseVeryLong_isCritical() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .lastPauseMs(600).maxPauseMs(600).longPauseCount(1).build()));
        assertEquals(GcDoctor.Level.CRITICAL, verdict.level());
        assertTrue(verdict.summary().contains("600 ms"));
    }

    @Test
    public void lastPauseElevated_isWarn() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .lastPauseMs(150).maxPauseMs(150).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("watch for repeats"));
    }

    @Test
    public void worstPauseHighButCurrentNormal_isWarn_oneOff() {
        // No recent-window figure set → falls back to all-time maxPauseMs.
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .maxPauseMs(300).lastPauseMs(50).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("one-off"));
    }

    @Test
    public void recentMaxPausePreferredOverAllTime() {
        // A single old spike (all-time 300) that is outside the last hour (recent 10) must NOT warn.
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .maxPauseMs(300).maxPauseRecentMs(10L).lastPauseMs(20).build()));
        assertEquals(GcDoctor.Level.OK, verdict.level());
    }

    @Test
    public void recurringLongPauses_isCritical() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .longPauseCount(5).longPauseThresholdMs(200).build()));
        assertEquals(GcDoctor.Level.CRITICAL, verdict.level());
        assertTrue(verdict.summary().contains("recurring long stalls"));
    }

    @Test
    public void liveSetNearHeapMax_isCritical() {
        long near = (long) (HEAP_MAX * 0.85);
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .liveSetAfterBytes(near).prevLiveSetAfterBytes(near).build()));
        assertEquals(GcDoctor.Level.CRITICAL, verdict.level());
        assertTrue(verdict.summary().contains("OOM"));
    }

    @Test
    public void liveSetTight_notFlaggedBelowThreeCollections() {
        // Rule 4 needs a few collections first — one early reading is not yet a trend.
        long near = (long) (HEAP_MAX * 0.85);
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .collectionCount(2).liveSetAfterBytes(near).prevLiveSetAfterBytes(near).build()));
        assertEquals(GcDoctor.Level.OK, verdict.level());
    }

    @Test
    public void liveSetGettingTight_isWarn() {
        long tight = (long) (HEAP_MAX * 0.70);
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .liveSetAfterBytes(tight).prevLiveSetAfterBytes(tight).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("getting tight"));
    }

    @Test
    public void oldGenGrowingOverAWindow_isWarn_possibleLeak() {
        // 3 samples over 2 h; old gen climbs 50→70 MB against a 100 MB ceiling = 10 %/h > 5 %/h.
        long t0 = 1_000_000_000_000L;
        List<TLiveSetSample> samples = List.of(
                TLiveSetSample.builder().epochMs(t0).usedBytes(50_000_000L).build(),
                TLiveSetSample.builder().epochMs(t0 + HOUR_MS).usedBytes(60_000_000L).build(),
                TLiveSetSample.builder().epochMs(t0 + 2 * HOUR_MS).usedBytes(70_000_000L).build());
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .oldGenMaxBytes(100_000_000L).oldGenSamples(samples).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("Old gen after GC grew"));
    }

    @Test
    public void oldGenFlatOverAWindow_isOk() {
        // 40 h of near-flat old gen (the mui-1 case): +8 KB, well under 5 %/h.
        long t0 = 1_000_000_000_000L;
        List<TLiveSetSample> samples = List.of(
                TLiveSetSample.builder().epochMs(t0).usedBytes(30_509_000L).build(),
                TLiveSetSample.builder().epochMs(t0 + 18 * HOUR_MS).usedBytes(30_513_000L).build(),
                TLiveSetSample.builder().epochMs(t0 + 36 * HOUR_MS).usedBytes(30_517_000L).build());
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .oldGenMaxBytes(100_000_000L).oldGenSamples(samples).build()));
        assertEquals(GcDoctor.Level.OK, verdict.level());
    }

    @Test
    public void idle_isOk_andLeadsSummary() {
        TGcInfo gc = healthyGc()
                .collectionCount(225)
                .avgGcIntervalMs(3_433_000.0)   // ~57 min between collections
                .allocatedBytesTotal(1_000_000L)
                .build();
        TSystemInfo info = TSystemInfo.builder()
                .heapMaxBytes(HEAP_MAX)
                .uptimeMs(3_600_000L)            // 1 000 000 B / 3600 s ≈ 278 B/s ≪ 100 KiB/s
                .gc(gc)
                .build();
        GcDoctor.Verdict verdict = GcDoctor.diagnose(info);
        assertEquals(GcDoctor.Level.OK, verdict.level());
        assertTrue(verdict.summary().contains("nearly idle"));
        assertEquals(verdict.summary(), verdict.findings().get(0).message());
    }

    @Test
    public void hostSwappingOut_isWarn() {
        TSystemInfo info = TSystemInfo.builder()
                .heapMaxBytes(HEAP_MAX).gc(healthyGc().build())
                .swapOutPagesPerSec(120.0)
                .build();
        GcDoctor.Verdict verdict = GcDoctor.diagnose(info);
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("swapping out"));
    }

    @Test
    public void jvmPagesSwappedOut_isWarn() {
        TSystemInfo info = TSystemInfo.builder()
                .heapMaxBytes(HEAP_MAX).gc(healthyGc().build())
                .processSwapBytes(32L * 1024 * 1024)   // 32 MiB > 16 MiB
                .build();
        GcDoctor.Verdict verdict = GcDoctor.diagnose(info);
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("swapped out"));
    }

    @Test
    public void lowMemAvailable_isWarn_usesMemAvailableOnly() {
        TSystemInfo info = TSystemInfo.builder()
                .heapMaxBytes(HEAP_MAX).gc(healthyGc().build())
                .physicalTotalBytes(16_000_000_000L)
                .memAvailableBytes(400_000_000L)       // 2.5 % of total
                .build();
        GcDoctor.Verdict verdict = GcDoctor.diagnose(info);
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("MemAvailable"));
    }

    @Test
    public void memAvailableNull_doesNotFallBackToMemFree() {
        // Only MemFree is tiny; MemAvailable was not collected → the rule must NOT fire.
        TSystemInfo info = TSystemInfo.builder()
                .heapMaxBytes(HEAP_MAX).gc(healthyGc().build())
                .physicalTotalBytes(16_000_000_000L)
                .physicalFreeBytes(200_000_000L)       // 1.25 % — but this is MemFree, not the signal
                .build();
        GcDoctor.Verdict verdict = GcDoctor.diagnose(info);
        assertEquals(GcDoctor.Level.OK, verdict.level());
    }
}
