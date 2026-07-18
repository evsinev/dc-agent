package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;
import com.payneteasy.dcagent.operator.service.agent.model.TAgentMetrics;

import java.time.Duration;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Maps the agent's raw {@link TSystemInfo} into {@link TAgentMetrics}: keeps the raw values (for
 * sorting) and adds human-readable {@code *Text} renderings (bytes → KB/MB/GB, fraction → %,
 * nanos/millis → duration). Derives used-memory and used-fraction from the raw readings.
 */
final class AgentMetricsMapper {

    private AgentMetricsMapper() {
    }

    static TAgentMetrics toMetrics(String aAgentName, TSystemInfo aInfo) {
        long physicalUsed = aInfo.getPhysicalTotalBytes() >= 0 && aInfo.getPhysicalFreeBytes() >= 0
                ? aInfo.getPhysicalTotalBytes() - aInfo.getPhysicalFreeBytes()
                : -1;
        double heapFraction = aInfo.getHeapMaxBytes() > 0
                ? (double) aInfo.getHeapUsedBytes() / aInfo.getHeapMaxBytes()
                : -1;
        double physicalFraction = aInfo.getPhysicalTotalBytes() > 0 && physicalUsed >= 0
                ? (double) physicalUsed / aInfo.getPhysicalTotalBytes()
                : -1;

        // Rich per-pause GC figures. gc is nullable (agent has no GC stats collector, or metrics
        // predate it) → fall back to the -1 / "n/a" sentinels used everywhere else here.
        TGcInfo          gc      = aInfo.getGc();
        GcDoctor.Verdict verdict = GcDoctor.diagnose(aInfo);
        String           detail  = verdict.findings().isEmpty()
                ? verdict.summary()
                : verdict.findings().stream().map(GcDoctor.Finding::message).collect(Collectors.joining("\n"));
        String           payload = GcLlmPayloadBuilder.build(aAgentName, aInfo);

        long   gcMaxPauseMs  = gc != null ? gc.getMaxPauseMs() : -1;
        long   gcLastPauseMs = gc != null ? gc.getLastPauseMs() : -1;
        long   gcLiveSet     = gc != null ? gc.getLiveSetAfterBytes() : -1;

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
                .heapUsedText(MetricFormat.bytes(aInfo.getHeapUsedBytes()))
                .heapCommittedBytes(aInfo.getHeapCommittedBytes())
                .heapCommittedText(MetricFormat.bytes(aInfo.getHeapCommittedBytes()))
                .heapMaxBytes(aInfo.getHeapMaxBytes())
                .heapMaxText(MetricFormat.bytes(aInfo.getHeapMaxBytes()))
                .heapUsedFraction(heapFraction)
                .heapUsedPercentText(percent(heapFraction))
                .nonHeapUsedBytes(aInfo.getNonHeapUsedBytes())
                .nonHeapUsedText(MetricFormat.bytes(aInfo.getNonHeapUsedBytes()))
                .physicalUsedBytes(physicalUsed)
                .physicalUsedText(MetricFormat.bytes(physicalUsed))
                .physicalTotalBytes(aInfo.getPhysicalTotalBytes())
                .physicalTotalText(MetricFormat.bytes(aInfo.getPhysicalTotalBytes()))
                .physicalFreeBytes(aInfo.getPhysicalFreeBytes())
                .physicalFreeText(MetricFormat.bytes(aInfo.getPhysicalFreeBytes()))
                .physicalUsedFraction(physicalFraction)
                .physicalUsedPercentText(percent(physicalFraction))
                .swapTotalBytes(aInfo.getSwapTotalBytes())
                .swapTotalText(MetricFormat.bytes(aInfo.getSwapTotalBytes()))
                .swapFreeBytes(aInfo.getSwapFreeBytes())
                .swapFreeText(MetricFormat.bytes(aInfo.getSwapFreeBytes()))
                .threadCount(aInfo.getThreadCount())
                .gcCount(aInfo.getGcCount())
                .gcTimeMs(aInfo.getGcTimeMs())
                .gcTimeText(duration(aInfo.getGcTimeMs()))
                .gcAvgPauseMs(gcAvgPauseMs(gc))
                .gcAvgPauseText(gcAvgPauseText(gc))
                .gcMaxPauseMs(gcMaxPauseMs)
                .gcMaxPauseText(pauseText(gcMaxPauseMs))
                .gcLastPauseMs(gcLastPauseMs)
                .gcLastPauseText(pauseText(gcLastPauseMs))
                .gcLongPauseCount(gc != null ? gc.getLongPauseCount() : 0)
                .gcLiveSetBytes(gcLiveSet)
                .gcLiveSetText(MetricFormat.bytes(gcLiveSet))
                .gcLastCause(gcLastCause(gc))
                .gcHealthLevel(verdict.level().name())
                .gcHealthSummary(verdict.summary())
                .gcHealthDetail(detail)
                .gcLlmPayload(payload)
                .build();
    }

    private static String percent(double aFraction) {
        return aFraction < 0 ? "n/a" : Math.round(aFraction * 100) + "%";
    }

    private static String pauseText(long aMillis) {
        return aMillis < 0 ? "n/a" : aMillis + " ms";
    }

    private static long gcAvgPauseMs(TGcInfo aGc) {
        return aGc != null && aGc.getAvgPauseMs() >= 0 ? Math.round(aGc.getAvgPauseMs()) : -1;
    }

    private static String gcAvgPauseText(TGcInfo aGc) {
        return aGc != null && aGc.getAvgPauseMs() >= 0
                ? String.format(Locale.ROOT, "%.1f ms", aGc.getAvgPauseMs())
                : "n/a";
    }

    private static String gcLastCause(TGcInfo aGc) {
        return aGc != null && aGc.getLastCause() != null ? aGc.getLastCause() : "n/a";
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
