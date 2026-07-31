package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression test built from the real {@code mui-1} snapshot (analysed 2026-07-31), the case that made
 * the report lie: a live set inflated by non-heap, CPU time mislabelled as uptime, and MemFree read as
 * pressure. The values below are the actual readings; the fixed payload must render them honestly.
 *
 * <p>A second variant nulls every field a newer agent added, emulating an older agent on the wire: the
 * payload must show {@code n/a} (never a fabricated 0) and the doctor's new rules must stay silent.
 */
public class GcLlmPayloadBuilderTest {

    // --- mui-1 actual readings ---
    private static final long HEAP_USED      = 33_134_285L;   // 31.6 MiB
    private static final long HEAP_MAX       = 129_814_528L;  // 123.8 MiB
    private static final long NON_HEAP_USED  = 47_500_000L;   // 45.3 MiB
    private static final long LIVE_SET       = 31_249_408L;   // 29.8 MiB (was 78.8 MiB before the T1 fix)
    private static final long OLD_GEN_MAX    = 89_522_176L;   // 85.4 MiB
    private static final long CPU_TIME_NANOS = 391_000_000_000L;
    private static final long UPTIME_MS      = 688_977_906L;  // 7d 23h 22m
    private static final long PHYS_TOTAL     = 16_535_000_000L;
    private static final long MEM_FREE       = 392_400_000L;  // 374.2 MiB
    private static final long MEM_AVAILABLE  = 6_552_000_000L; // 6.1 GiB
    private static final long SWAP_TOTAL     = 8_589_934_592L;
    private static final long SWAP_FREE      = 6_763_000_000L;
    private static final long ALLOCATED      = 7_478_000_000L; // / uptime ≈ 10.6 KiB/s
    private static final double AVG_INTERVAL = 3_433_000.0;    // ~57 min

    private static TGcInfo.TGcInfoBuilder mui1Gc() {
        return TGcInfo.builder()
                .collectionCount(225)
                .totalPauseMs(183)
                .avgPauseMs(0.8)
                .maxPauseMs(24)
                .lastPauseMs(1)
                .lastGcEpochMs(UPTIME_MS)
                .longPauseCount(0)
                .longPauseThresholdMs(200)
                .liveSetAfterBytes(LIVE_SET)
                .prevLiveSetAfterBytes(LIVE_SET)
                .lastCause("Copy")
                .lastAction("end of minor GC");
    }

    private static TSystemInfo.TSystemInfoBuilder mui1System() {
        return TSystemInfo.builder()
                .heapUsedBytes(HEAP_USED)
                .heapCommittedBytes(HEAP_MAX)
                .heapMaxBytes(HEAP_MAX)
                .nonHeapUsedBytes(NON_HEAP_USED)
                .processCpuTimeNanos(CPU_TIME_NANOS)
                .physicalTotalBytes(PHYS_TOTAL)
                .physicalFreeBytes(MEM_FREE)
                .swapTotalBytes(SWAP_TOTAL)
                .swapFreeBytes(SWAP_FREE)
                .availableProcessors(4);
    }

    /** The fully-populated (current-agent) snapshot. */
    private static TSystemInfo populated() {
        TGcInfo gc = mui1Gc()
                .collectorNames(List.of("Copy", "MarkSweepCompact"))
                .allocatedBytesTotal(ALLOCATED)
                .firstGcEpochMs(1L)
                .avgGcIntervalMs(AVG_INTERVAL)
                .fullGcCount(1L)
                .oldGenMaxBytes(OLD_GEN_MAX)
                .oldGenUsedAfterGcBytes(30_517_000L)
                .maxPauseRecentMs(2L)
                .subMsPauseCount(40L)
                .build();
        return mui1System()
                .uptimeMs(UPTIME_MS)
                .memAvailableBytes(MEM_AVAILABLE)
                .swapCachedBytes(12_641_280L)
                .swapInPagesPerSec(0.0)
                .swapOutPagesPerSec(0.0)
                .processSwapBytes(0L)
                .gc(gc)
                .build();
    }

    /** The same snapshot as seen from an older agent: every added field absent (null). */
    private static TSystemInfo oldAgent() {
        return mui1System().gc(mui1Gc().build()).build();
    }

    @Test
    public void mui1_payload_isHonestAboutUptimeAndLiveSet() {
        String payload = GcLlmPayloadBuilder.build("mui-1", populated());

        // Uptime and CPU time are two clearly separate lines; nothing calls CPU time "uptime".
        assertTrue(payload.contains("uptime (wall clock): 7d 23h 22m"));
        assertTrue(payload.contains("process CPU time: 6m 31s"));
        assertFalse(payload.contains("of CPU"));

        // Live set is heap-only and consistent with heap used — well under heap max, not inflated by non-heap.
        assertTrue(payload.contains("live set (heap only, after last GC): 29.8 MiB"));
        assertTrue("live set must stay under heap max", LIVE_SET < HEAP_MAX);
        assertTrue("live set must not exceed heap used", LIVE_SET <= HEAP_USED);

        // Configuration + activity are explicit.
        assertTrue(payload.contains("collectors: Copy, MarkSweepCompact"));
        assertTrue(payload.contains("heap max: 123.8 MiB"));
        assertTrue(payload.contains("old gen max: 85.4 MiB"));
        assertTrue(payload.contains("collections: 225"));
        assertTrue(payload.contains("avg interval between collections: 57m 13s"));
        assertTrue(payload.contains("allocation rate: 10.6 KiB/s"));

        // Host memory: MemFree explicitly de-emphasised, MemAvailable present, swap summarised.
        assertTrue(payload.contains("MemFree (excludes page cache — not a pressure signal on DB hosts): 374.2 MiB"));
        assertTrue(payload.contains("MemAvailable: 6.1 GiB"));
        assertTrue(payload.contains("swap used: 1.7 GiB of 8.0 GiB"));
        assertTrue(payload.contains("this JVM swapped out: 0 B"));

        // Sections + caveats.
        assertTrue(payload.contains("## GC configuration"));
        assertTrue(payload.contains("## Activity"));
        assertTrue(payload.contains("## Host memory pressure"));
        assertTrue(payload.contains("sub-millisecond pauses (reported as 0 ms): 40"));
        assertTrue(payload.contains("treat as unknown, not as zero"));
        assertTrue(payload.contains("sub-millisecond pauses are\nreported as 0")
                || payload.contains("sub-millisecond pauses are reported as 0"));
    }

    @Test
    public void mui1_doctor_isOk_withIdleLeadingTheSummary() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(populated());
        assertTrue(verdict.level() == GcDoctor.Level.OK);
        assertTrue(verdict.summary().contains("nearly idle"));
        assertTrue(verdict.summary().equals(verdict.findings().get(0).message()));
    }

    @Test
    public void oldAgent_payload_showsNaNotZero() {
        String payload = GcLlmPayloadBuilder.build("mui-1", oldAgent());
        assertTrue(payload.contains("uptime (wall clock): n/a"));
        assertTrue(payload.contains("allocation rate: n/a"));
        assertTrue(payload.contains("collectors: n/a"));
        assertTrue(payload.contains("MemAvailable: n/a"));
        assertTrue(payload.contains("old gen max: n/a"));
        assertTrue(payload.contains("max pause (last hour): n/a"));
        assertTrue(payload.contains("swap out: n/a"));
        assertTrue(payload.contains("this JVM swapped out: n/a"));
        // process CPU time is a primitive with a -1 sentinel, still present from an old agent.
        assertTrue(payload.contains("process CPU time: 6m 31s"));
    }

    @Test
    public void oldAgent_doctor_firesNoNewRules() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(oldAgent());
        assertTrue(verdict.level() == GcDoctor.Level.OK);
        assertFalse(verdict.summary().contains("nearly idle"));
        assertFalse(verdict.summary().contains("swapping"));
        assertFalse(verdict.summary().contains("MemAvailable"));
    }

    @Test
    public void payload_containsAgentNameAndInstruction() {
        String payload = GcLlmPayloadBuilder.build("test-agent", populated());
        assertTrue(payload.contains("test-agent"));
        assertTrue(payload.contains("You are a JVM garbage-collection expert"));
        assertTrue(payload.contains("If the service is idle"));
    }

    @Test
    public void payload_nullGc_saysNothingToAnalyse() {
        String payload = GcLlmPayloadBuilder.build("test-agent", TSystemInfo.builder().build());
        assertTrue(payload.contains("test-agent"));
        assertTrue(payload.contains("No garbage collections have happened yet"));
        assertFalse(payload.contains("## GC configuration"));
    }
}
