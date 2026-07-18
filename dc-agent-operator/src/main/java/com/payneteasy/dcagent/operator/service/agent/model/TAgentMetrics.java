package com.payneteasy.dcagent.operator.service.agent.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

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
    long   physicalFreeBytes;
    String physicalFreeText;
    double physicalUsedFraction;   // used/total, 0..1, -1 = n/a
    String physicalUsedPercentText;
    long   swapTotalBytes;
    String swapTotalText;
    long   swapFreeBytes;
    String swapFreeText;

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

    // Deterministic verdict (no LLM). Level is OK / WARN / CRITICAL for a status indicator.
    String gcHealthLevel;
    String gcHealthSummary;       // one-line headline
    String gcHealthDetail;        // full multi-line findings, newline-joined

    // Ready-to-paste block for an LLM (the "copy for LLM" button copies this verbatim).
    String gcLlmPayload;
}
