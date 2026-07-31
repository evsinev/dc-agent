package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Raw JVM / OS metrics read from the agent's own MXBeans (see SystemInfoCollector in dc-agent-app).
 * All values are raw for sorting; the operator formats them into human-readable strings.
 * Sentinels: CPU loads and load average are -1 when unavailable; heap max is -1 when undefined.
 *
 * <p>Existing primitive fields keep the -1 "unavailable" sentinel. Fields added later are BOXED
 * ({@link Long}/{@link Double}) so that {@code null} means "this agent version did not collect it":
 * Gson deserializes these models without a constructor (Unsafe allocation), so an absent primitive
 * would silently arrive as {@code 0}, indistinguishable from a real reading — a boxed field arrives
 * as {@code null} instead. The operator renders {@code null} as {@code n/a} and never uses it in a rule.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TSystemInfo {

    // CPU (com.sun.management.OperatingSystemMXBean + Runtime)
    double systemCpuLoad;        // 0..1, -1 if unavailable
    double processCpuLoad;       // 0..1, -1 if unavailable
    double loadAverage;          // getSystemLoadAverage(), -1 on Windows/unsupported
    int    availableProcessors;
    long   processCpuTimeNanos;  // -1 if unavailable
    Long   uptimeMs;             // RuntimeMXBean.getUptime() — wall-clock process age; null if not collected

    // JVM memory (MemoryMXBean)
    long   heapUsedBytes;
    long   heapCommittedBytes;
    long   heapMaxBytes;         // -1 if undefined
    long   nonHeapUsedBytes;

    // Physical memory + swap (com.sun.management.OperatingSystemMXBean)
    long   physicalTotalBytes;
    long   physicalFreeBytes;    // Linux MemFree — excludes page cache / reclaimable slab
    long   swapTotalBytes;
    long   swapFreeBytes;

    // Linux /proc signals (LinuxProcProbe). Boxed: null on non-Linux hosts or older agents.
    Long   memAvailableBytes;    // /proc/meminfo MemAvailable — the real "free-ish" figure
    Long   swapCachedBytes;      // /proc/meminfo SwapCached
    Double swapInPagesPerSec;    // /proc/vmstat pswpin rate; null until the second sample
    Double swapOutPagesPerSec;   // /proc/vmstat pswpout rate; null until the second sample
    Long   processSwapBytes;     // /proc/self/status VmSwap — how much of THIS JVM is swapped out

    // Threads (ThreadMXBean) + GC (GarbageCollectorMXBean, summed)
    int    threadCount;
    long   gcCount;
    long   gcTimeMs;

    // Rich per-pause GC statistics (GarbageCollectionNotificationInfo listener). Nullable if the
    // GC stats collector is not installed; the summed gcCount/gcTimeMs above remain the fallback.
    TGcInfo gc;
}
