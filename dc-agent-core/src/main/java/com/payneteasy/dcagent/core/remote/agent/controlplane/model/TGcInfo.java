package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Rich GC summary read from the agent's {@link com.sun.management.GarbageCollectionNotificationInfo}
 * listener (see GcStatsCollector in dc-agent-app). Complements the coarse {@code gcCount}/{@code
 * gcTimeMs} already in {@link TSystemInfo}: this carries per-pause statistics and the live-set trend
 * needed for a diagnosis, not just cumulative totals.
 *
 * <p>All values are raw; the operator formats and interprets them. Sentinels: -1 means "not sampled
 * yet / unavailable" (e.g. before the first GC).
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
}
