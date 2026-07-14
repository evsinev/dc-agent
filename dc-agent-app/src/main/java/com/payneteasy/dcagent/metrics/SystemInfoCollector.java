package com.payneteasy.dcagent.metrics;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;

/**
 * Reads JVM/OS metrics from MXBean handles that are looked up once and cached (the getters cost
 * microseconds). CPU load is served from the background {@link CpuLoadSampler}. Deliberately does
 * NOT iterate memory pools or dump threads (too heavy for a hot path).
 */
public class SystemInfoCollector {

    private final java.lang.management.OperatingSystemMXBean osBean;
    private final com.sun.management.OperatingSystemMXBean   sunOsBean; // nullable on non-HotSpot JVMs
    private final MemoryMXBean                               memoryBean;
    private final ThreadMXBean                               threadBean;
    private final CpuLoadSampler                             cpuLoadSampler;

    public SystemInfoCollector() {
        osBean         = ManagementFactory.getOperatingSystemMXBean();
        sunOsBean      = osBean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
        memoryBean     = ManagementFactory.getMemoryMXBean();
        threadBean     = ManagementFactory.getThreadMXBean();
        cpuLoadSampler = new CpuLoadSampler(sunOsBean);
    }

    /** Start the background CPU-load sampler. Call once at startup. */
    public void start() {
        cpuLoadSampler.start(2000);
    }

    public TSystemInfo collect() {
        MemoryUsage heap    = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

        long gcCount = 0;
        long gcTime  = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time  = gc.getCollectionTime();
            if (count > 0) {
                gcCount += count;
            }
            if (time > 0) {
                gcTime += time;
            }
        }

        return TSystemInfo.builder()
                .systemCpuLoad(sanitizeLoad(cpuLoadSampler.getSystemCpuLoad()))
                .processCpuLoad(sanitizeLoad(cpuLoadSampler.getProcessCpuLoad()))
                .loadAverage(osBean.getSystemLoadAverage())
                .availableProcessors(osBean.getAvailableProcessors())
                .processCpuTimeNanos(sunOsBean != null ? sunOsBean.getProcessCpuTime() : -1)
                .heapUsedBytes(heap.getUsed())
                .heapCommittedBytes(heap.getCommitted())
                .heapMaxBytes(heap.getMax())
                .nonHeapUsedBytes(nonHeap.getUsed())
                .physicalTotalBytes(sunOsBean != null ? sunOsBean.getTotalMemorySize() : -1)
                .physicalFreeBytes(sunOsBean != null ? sunOsBean.getFreeMemorySize() : -1)
                .swapTotalBytes(sunOsBean != null ? sunOsBean.getTotalSwapSpaceSize() : -1)
                .swapFreeBytes(sunOsBean != null ? sunOsBean.getFreeSwapSpaceSize() : -1)
                .threadCount(threadBean.getThreadCount())
                .gcCount(gcCount)
                .gcTimeMs(gcTime)
                .build();
    }

    private static double sanitizeLoad(double aValue) {
        return Double.isNaN(aValue) || aValue < 0 ? -1 : aValue;
    }
}
