package com.payneteasy.dcagent.operator.service.agent.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

/**
 * Per-agent JVM/OS metrics for the operator UI. Each metric carries a raw value (for table sorting)
 * plus a human-readable {@code *Text} rendering (for display) — mirroring uptimeMs/uptimeFormatted.
 * CPU/heap/physical "fraction" values are 0..1 (or -1 when unavailable).
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TAgentMetrics {

    // CPU
    double systemCpuLoad;          // 0..1, -1 = n/a
    String systemCpuLoadText;
    double processCpuLoad;         // 0..1, -1 = n/a
    String processCpuLoadText;
    double loadAverage;            // -1 = n/a (Windows)
    String loadAverageText;
    int    availableProcessors;
    long   processCpuTimeNanos;
    String processCpuTimeText;
    Long   uptimeMs;               // wall-clock process age; null = n/a (older agent)
    String uptimeText;

    // JVM memory
    long   heapUsedBytes;
    String heapUsedText;
    long   heapCommittedBytes;
    String heapCommittedText;
    long   heapMaxBytes;
    String heapMaxText;
    double heapUsedFraction;       // used/max, 0..1, -1 = n/a
    String heapUsedPercentText;
    long   nonHeapUsedBytes;
    String nonHeapUsedText;

    // Physical memory + swap
    long   physicalUsedBytes;
    String physicalUsedText;
    long   physicalTotalBytes;
    String physicalTotalText;
    long   physicalFreeBytes;      // Linux MemFree (excludes page cache) — UI label "MemFree"
    String physicalFreeText;
    Long   memAvailableBytes;      // Linux MemAvailable — the real pressure figure; null = n/a
    String memAvailableText;
    double physicalUsedFraction;   // used/total, 0..1, -1 = n/a
    String physicalUsedPercentText;
    long   swapTotalBytes;
    String swapTotalText;
    long   swapFreeBytes;
    String swapFreeText;
    Long   swapCachedBytes;        // Linux SwapCached; null = n/a
    String swapCachedText;
    Double swapInPagesPerSec;      // /proc/vmstat pswpin rate; null = n/a / first sample
    String swapInText;
    Double swapOutPagesPerSec;     // /proc/vmstat pswpout rate; null = n/a / first sample
    String swapOutText;
    Long   processSwapBytes;       // VmSwap — how much of THIS JVM is swapped out; null = n/a
    String processSwapText;

    // Threads + GC (cumulative)
    int    threadCount;
    long   gcCount;
    long   gcTimeMs;
    String gcTimeText;

    // Rich GC statistics (per-pause). Raw values for sorting + *Text for display.
    long   gcAvgPauseMs;          // -1 = n/a
    String gcAvgPauseText;
    long   gcMaxPauseMs;          // -1 = n/a
    String gcMaxPauseText;
    long   gcLastPauseMs;         // -1 = n/a
    String gcLastPauseText;
    long   gcLongPauseCount;
    long   gcLiveSetBytes;        // heap used after last GC, -1 = n/a
    String gcLiveSetText;
    String gcLastCause;

    // Extended GC signals (boxed; null = n/a — older agent or not applicable to the collector).
    List<String> gcCollectorNames;
    String gcCollectorsText;              // joined, e.g. "Copy, MarkSweepCompact"
    Long   gcAllocationRateBytesPerSec;   // derived from allocated eden / uptime
    String gcAllocationRateText;          // e.g. "10.6 KiB/s"
    Long   gcAvgIntervalMs;               // mean gap between collections
    String gcAvgIntervalText;             // e.g. "57m 13s"
    Long   gcFullGcCount;
    Long   gcOldGenUsedBytes;
    String gcOldGenUsedText;
    Long   gcOldGenMaxBytes;
    String gcOldGenMaxText;
    Long   gcMaxPauseRecentMs;            // longest pause in the last sliding hour
    String gcMaxPauseRecentText;
    Long   gcSubMsPauseCount;             // collections reported as 0 ms (sub-millisecond, JMX resolution)

    // Deterministic verdict (no LLM). Level is OK / WARN / CRITICAL for a status indicator.
    String gcHealthLevel;
    String gcHealthSummary;       // one-line headline
    String gcHealthDetail;        // full multi-line findings, newline-joined

    // Ready-to-paste block for an LLM (the "copy for LLM" button copies this verbatim).
    String gcLlmPayload;
}
