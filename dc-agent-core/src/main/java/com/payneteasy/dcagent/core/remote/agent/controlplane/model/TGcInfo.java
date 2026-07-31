package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

/**
 * Rich GC summary read from the agent's {@link com.sun.management.GarbageCollectionNotificationInfo}
 * listener (see GcStatsCollector in dc-agent-app). Complements the coarse {@code gcCount}/{@code
 * gcTimeMs} already in {@link TSystemInfo}: this carries per-pause statistics and the live-set trend
 * needed for a diagnosis, not just cumulative totals.
 *
 * <p>All values are raw; the operator formats and interprets them. Sentinels: on the original primitive
 * fields, -1 means "not sampled yet / unavailable" (e.g. before the first GC). Fields added later are
 * BOXED ({@link Long}/{@link Double}) so {@code null} distinguishes "this agent version did not collect
 * it" from a real 0 — Gson deserializes without a constructor, so an absent primitive would arrive as 0.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TGcInfo {

    long   collectionCount;        // number of collections since start
    long   totalPauseMs;           // summed pause time
    double avgPauseMs;             // totalPauseMs / collectionCount, -1 if none
    long   maxPauseMs;             // longest single pause seen
    long   lastPauseMs;            // duration of the most recent pause, -1 if none yet
    long   lastGcEpochMs;          // wall-clock of the most recent GC, -1 if none yet
    long   longPauseCount;         // pauses at/over longPauseThresholdMs
    long   longPauseThresholdMs;   // the "long pause" cutoff used above

    long   liveSetAfterBytes;      // heap used right after the most recent GC, -1 if none yet
    long   prevLiveSetAfterBytes;  // live set after the GC before that, -1 if n/a

    String lastCause;              // e.g. "G1 Evacuation Pause", nullable
    String lastAction;             // e.g. "end of minor GC", nullable

    // --- added later; boxed, null = not collected by this agent version ---

    List<String> collectorNames;   // e.g. ["Copy","MarkSweepCompact"] — which collectors are running

    Long   allocatedBytesTotal;    // eden allocated since start; null on ZGC/Shenandoah (no eden pool)
    Long   firstGcEpochMs;         // wall-clock of the first observed GC
    Long   lastGcIntervalMs;       // gap between the last two collections
    Double avgGcIntervalMs;        // (lastGcEpochMs - firstGcEpochMs) / (collectionCount - 1)

    Long   fullGcCount;                  // number of major (full) collections
    Long   liveSetAfterFullGcBytes;      // heap used right after the most recent FULL GC
    Long   prevLiveSetAfterFullGcBytes;  // and the one before it
    Long   oldGenUsedAfterGcBytes;       // old-gen used after the most recent GC; null if no old pool
    Long   oldGenMaxBytes;               // old-gen capacity; null if no old pool
    List<TLiveSetSample> oldGenSamples;  // bounded old-gen-after-GC series for real-window growth math

    Long   maxPauseRecentMs;       // longest pause within the last sliding hour; null if none recorded

    Long   subMsPauseCount;        // collections JMX reported as 0 ms — real pause was sub-millisecond
}
