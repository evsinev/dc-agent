package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;
import com.payneteasy.dcagent.operator.service.agent.model.TAgentMetrics;

import java.time.Duration;
import java.util.Locale;

/**
 * Maps the agent's raw {@link TSystemInfo} into {@link TAgentMetrics}: keeps the raw values (for
 * sorting) and adds human-readable {@code *Text} renderings (bytes → KB/MB/GB, fraction → %,
 * nanos/millis → duration). Derives used-memory and used-fraction from the raw readings.
 */
final class AgentMetricsMapper {

    private AgentMetricsMapper() {
    }

    static TAgentMetrics toMetrics(TSystemInfo aInfo) {
        long physicalUsed = aInfo.getPhysicalTotalBytes() >= 0 && aInfo.getPhysicalFreeBytes() >= 0
                ? aInfo.getPhysicalTotalBytes() - aInfo.getPhysicalFreeBytes()
                : -1;
        double heapFraction = aInfo.getHeapMaxBytes() > 0
                ? (double) aInfo.getHeapUsedBytes() / aInfo.getHeapMaxBytes()
                : -1;
        double physicalFraction = aInfo.getPhysicalTotalBytes() > 0 && physicalUsed >= 0
                ? (double) physicalUsed / aInfo.getPhysicalTotalBytes()
                : -1;

        return TAgentMetrics.builder()
                .systemCpuLoad(aInfo.getSystemCpuLoad())
                .systemCpuLoadText(percent(aInfo.getSystemCpuLoad()))
                .processCpuLoad(aInfo.getProcessCpuLoad())
                .processCpuLoadText(percent(aInfo.getProcessCpuLoad()))
                .loadAverage(aInfo.getLoadAverage())
                .loadAverageText(aInfo.getLoadAverage() < 0 ? "n/a" : String.format(Locale.ROOT, "%.2f", aInfo.getLoadAverage()))
                .availableProcessors(aInfo.getAvailableProcessors())
                .processCpuTimeNanos(aInfo.getProcessCpuTimeNanos())
                .processCpuTimeText(aInfo.getProcessCpuTimeNanos() < 0 ? "n/a" : duration(aInfo.getProcessCpuTimeNanos() / 1_000_000))
                .heapUsedBytes(aInfo.getHeapUsedBytes())
                .heapUsedText(bytes(aInfo.getHeapUsedBytes()))
                .heapCommittedBytes(aInfo.getHeapCommittedBytes())
                .heapCommittedText(bytes(aInfo.getHeapCommittedBytes()))
                .heapMaxBytes(aInfo.getHeapMaxBytes())
                .heapMaxText(bytes(aInfo.getHeapMaxBytes()))
                .heapUsedFraction(heapFraction)
                .heapUsedPercentText(percent(heapFraction))
                .nonHeapUsedBytes(aInfo.getNonHeapUsedBytes())
                .nonHeapUsedText(bytes(aInfo.getNonHeapUsedBytes()))
                .physicalUsedBytes(physicalUsed)
                .physicalUsedText(bytes(physicalUsed))
                .physicalTotalBytes(aInfo.getPhysicalTotalBytes())
                .physicalTotalText(bytes(aInfo.getPhysicalTotalBytes()))
                .physicalFreeBytes(aInfo.getPhysicalFreeBytes())
                .physicalFreeText(bytes(aInfo.getPhysicalFreeBytes()))
                .physicalUsedFraction(physicalFraction)
                .physicalUsedPercentText(percent(physicalFraction))
                .swapTotalBytes(aInfo.getSwapTotalBytes())
                .swapTotalText(bytes(aInfo.getSwapTotalBytes()))
                .swapFreeBytes(aInfo.getSwapFreeBytes())
                .swapFreeText(bytes(aInfo.getSwapFreeBytes()))
                .threadCount(aInfo.getThreadCount())
                .gcCount(aInfo.getGcCount())
                .gcTimeMs(aInfo.getGcTimeMs())
                .gcTimeText(duration(aInfo.getGcTimeMs()))
                .build();
    }

    private static String percent(double aFraction) {
        return aFraction < 0 ? "n/a" : Math.round(aFraction * 100) + "%";
    }

    private static String bytes(long aBytes) {
        if (aBytes < 0) {
            return "n/a";
        }
        if (aBytes < 1024) {
            return aBytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = aBytes;
        int    unit  = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String duration(long aMillis) {
        if (aMillis < 0) {
            return "n/a";
        }
        Duration d       = Duration.ofMillis(aMillis);
        long     days    = d.toDays();
        long     hours   = d.toHoursPart();
        long     minutes = d.toMinutesPart();
        long     seconds = d.toSecondsPart();
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        if (seconds > 0) {
            return seconds + "s";
        }
        return aMillis + "ms";
    }
}
