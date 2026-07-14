package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * Raw JVM / OS metrics read from the agent's own MXBeans (see SystemInfoCollector in dc-agent-app).
 * All values are raw for sorting; the operator formats them into human-readable strings.
 * Sentinels: CPU loads and load average are -1 when unavailable; heap max is -1 when undefined.
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

    // JVM memory (MemoryMXBean)
    long   heapUsedBytes;
    long   heapCommittedBytes;
    long   heapMaxBytes;         // -1 if undefined
    long   nonHeapUsedBytes;

    // Physical memory + swap (com.sun.management.OperatingSystemMXBean)
    long   physicalTotalBytes;
    long   physicalFreeBytes;
    long   swapTotalBytes;
    long   swapFreeBytes;

    // Threads (ThreadMXBean) + GC (GarbageCollectorMXBean, summed)
    int    threadCount;
    long   gcCount;
    long   gcTimeMs;
}
