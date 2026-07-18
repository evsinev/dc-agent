package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Each test crafts a {@link TGcInfo} that isolates a single rule so we can assert both the level and
 * the honest wording. The heap max is fixed at 1 GB so the live-set fractions are easy to reason
 * about.
 */
public class GcDoctorTest {

    private static final long HEAP_MAX = 1_000_000_000L; // ~1 GB, keeps the live-set math obvious

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
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .maxPauseMs(300).lastPauseMs(50).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("one-off"));
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
    public void liveSetGettingTight_isWarn() {
        long tight = (long) (HEAP_MAX * 0.70);
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .liveSetAfterBytes(tight).prevLiveSetAfterBytes(tight).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("getting tight"));
    }

    @Test
    public void liveSetJumped_isWarn_possibleLeak() {
        GcDoctor.Verdict verdict = GcDoctor.diagnose(sys(healthyGc()
                .prevLiveSetAfterBytes(100_000_000L).liveSetAfterBytes(200_000_000L).build()));
        assertEquals(GcDoctor.Level.WARN, verdict.level());
        assertTrue(verdict.summary().contains("Live set grew"));
    }

    @Test
    public void payload_containsAgentName_instruction_andRawNumbers() {
        String payload = GcLlmPayloadBuilder.build("test-agent", sys(healthyGc()
                .collectionCount(10).maxPauseMs(600).lastPauseMs(600).build()));
        assertTrue(payload.contains("test-agent"));
        assertTrue(payload.contains("You are a JVM garbage-collection expert"));
        assertTrue(payload.contains("collections: 10"));
        assertTrue(payload.contains("max pause: 600 ms"));
        assertTrue(payload.contains("last cause: G1 Evacuation Pause"));
    }

    @Test
    public void payload_nullGc_saysNothingToAnalyse() {
        String payload = GcLlmPayloadBuilder.build("test-agent", TSystemInfo.builder().build());
        assertTrue(payload.contains("test-agent"));
        assertTrue(payload.contains("No garbage collections have happened yet"));
        assertFalse(payload.contains("## GC statistics"));
    }
}
