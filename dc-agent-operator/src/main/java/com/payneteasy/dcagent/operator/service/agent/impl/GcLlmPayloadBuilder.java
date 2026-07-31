package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;

import java.time.Duration;
import java.util.Locale;

/**
 * Builds a self-contained plain-text block the operator can copy and paste into an LLM. It bundles
 * the raw GC readings plus the surrounding host/JVM context (heap sizing, physical memory, swap,
 * CPU) and a short instruction, so the model has everything it needs to reason the way a human
 * would over a GC log — including context the JVM itself cannot see (that memory is shared with
 * other processes on the box).
 *
 * <p>This is the "escape hatch" beside the deterministic {@link GcDoctor} verdict: the rules give an
 * instant, stable answer; this payload lets a model give a contextual one when the rules are not
 * enough.
 */
final class GcLlmPayloadBuilder {

    private static final String MS_LINE = " ms\n";

    private GcLlmPayloadBuilder() {
    }

    static String build(String aAgentName, TSystemInfo aInfo) {
        TGcInfo gc = aInfo == null ? null : aInfo.getGc();
        StringBuilder b = new StringBuilder(1024);

        b.append("You are a JVM garbage-collection expert. Below are GC statistics and host context ")
         .append("collected from a running Java service. Explain in plain language what is happening ")
         .append("with GC, whether anything looks wrong, and what to check or change. Note that heap ")
         .append("memory is shared with other processes on the host, so consider host memory pressure ")
         .append("and swap, not just the JVM. If the service is idle (very low allocation rate, long ")
         .append("intervals between collections), say so first — GC statistics from an idle JVM say ")
         .append("little about how it behaves under load.\n\n");

        b.append("## Agent\n");
        b.append("name: ").append(aAgentName).append('\n');
        b.append('\n');

        if (gc == null || gc.getCollectionCount() <= 0) {
            b.append("## GC\nNo garbage collections have happened yet — nothing to analyse.\n");
            return b.toString();
        }

        // aInfo is guaranteed non-null here: gc is non-null (we returned above otherwise), and gc is
        // only non-null when aInfo was non-null (see the gc assignment at the top of this method).

        b.append("## GC configuration\n");
        b.append("collectors: ").append(collectors(gc)).append('\n');
        b.append("heap max: ").append(MetricFormat.bytes(aInfo.getHeapMaxBytes())).append('\n');
        b.append("old gen max: ").append(bytesN(gc.getOldGenMaxBytes())).append('\n');
        b.append('\n');

        b.append("## Activity\n");
        b.append("uptime (wall clock): ")
         .append(aInfo.getUptimeMs() == null ? "n/a" : duration(aInfo.getUptimeMs())).append('\n');
        b.append("process CPU time: ")
         .append(aInfo.getProcessCpuTimeNanos() < 0 ? "n/a" : duration(aInfo.getProcessCpuTimeNanos() / 1_000_000))
         .append('\n');
        b.append("collections: ").append(gc.getCollectionCount()).append('\n');
        b.append("avg interval between collections: ").append(avgIntervalText(gc)).append('\n');
        b.append("allocation rate: ").append(allocationRate(gc, aInfo.getUptimeMs())).append('\n');
        b.append('\n');

        b.append("## GC pauses\n");
        b.append("total pause: ").append(gc.getTotalPauseMs()).append(MS_LINE);
        b.append("avg pause: ").append(fmt(gc.getAvgPauseMs())).append(MS_LINE);
        b.append("max pause (all time): ").append(gc.getMaxPauseMs()).append(MS_LINE);
        b.append("max pause (last hour): ")
         .append(gc.getMaxPauseRecentMs() == null ? "n/a" : gc.getMaxPauseRecentMs() + " ms").append('\n');
        b.append("last pause: ").append(gc.getLastPauseMs()).append(MS_LINE);
        b.append("pauses over ").append(gc.getLongPauseThresholdMs()).append(" ms: ")
         .append(gc.getLongPauseCount()).append('\n');
        b.append("sub-millisecond pauses (reported as 0 ms): ")
         .append(gc.getSubMsPauseCount() == null ? "n/a" : gc.getSubMsPauseCount()).append('\n');
        b.append("last cause: ").append(gc.getLastCause() == null ? "n/a" : gc.getLastCause()).append('\n');
        b.append("last action: ").append(gc.getLastAction() == null ? "n/a" : gc.getLastAction()).append('\n');
        b.append("live set (heap only, after last GC): ")
         .append(MetricFormat.bytes(gc.getLiveSetAfterBytes())).append('\n');
        b.append("live set after previous GC: ")
         .append(MetricFormat.bytes(gc.getPrevLiveSetAfterBytes())).append('\n');
        b.append('\n');

        b.append("## Host memory pressure\n");
        b.append("heap used: ").append(MetricFormat.bytes(aInfo.getHeapUsedBytes())).append('\n');
        b.append("non-heap used: ").append(MetricFormat.bytes(aInfo.getNonHeapUsedBytes())).append('\n');
        b.append("physical total: ").append(MetricFormat.bytes(aInfo.getPhysicalTotalBytes())).append('\n');
        b.append("MemFree (excludes page cache — not a pressure signal on DB hosts): ")
         .append(MetricFormat.bytes(aInfo.getPhysicalFreeBytes())).append('\n');
        b.append("MemAvailable: ").append(bytesN(aInfo.getMemAvailableBytes())).append('\n');
        b.append("swap used: ").append(MetricFormat.bytes(swapUsed(aInfo)))
         .append(" of ").append(MetricFormat.bytes(aInfo.getSwapTotalBytes())).append('\n');
        b.append("swap in: ").append(pagesN(aInfo.getSwapInPagesPerSec())).append('\n');
        b.append("swap out: ").append(pagesN(aInfo.getSwapOutPagesPerSec())).append('\n');
        b.append("this JVM swapped out: ").append(bytesN(aInfo.getProcessSwapBytes())).append('\n');
        b.append('\n');

        b.append("## CPU context\n");
        b.append("processors: ").append(aInfo.getAvailableProcessors()).append('\n');
        b.append("load average: ").append(aInfo.getLoadAverage() < 0 ? "n/a"
                : String.format(Locale.ROOT, "%.2f", aInfo.getLoadAverage())).append('\n');
        b.append("system CPU: ").append(pct(aInfo.getSystemCpuLoad())).append('\n');
        b.append("process CPU: ").append(pct(aInfo.getProcessCpuLoad())).append('\n');
        b.append("threads: ").append(aInfo.getThreadCount()).append('\n');
        b.append('\n');

        b.append("Fields marked n/a were not collected (older agent or non-Linux host) — treat as ")
         .append("unknown, not as zero.\n");
        b.append("Pause durations come from JMX with 1 ms resolution; sub-millisecond pauses are ")
         .append("reported as 0, so total and average pause are understated for low-pause collectors.\n");

        return b.toString();
    }

    private static long swapUsed(TSystemInfo aInfo) {
        return aInfo.getSwapTotalBytes() >= 0 && aInfo.getSwapFreeBytes() >= 0
                ? aInfo.getSwapTotalBytes() - aInfo.getSwapFreeBytes()
                : -1;
    }

    private static String bytesN(Long aBytes) {
        return aBytes == null ? "n/a" : MetricFormat.bytes(aBytes);
    }

    private static String pagesN(Double aRate) {
        return aRate == null ? "n/a" : String.format(Locale.ROOT, "%.1f pages/s", aRate);
    }

    private static String collectors(TGcInfo aGc) {
        return aGc.getCollectorNames() != null && !aGc.getCollectorNames().isEmpty()
                ? String.join(", ", aGc.getCollectorNames())
                : "n/a";
    }

    private static String avgIntervalText(TGcInfo aGc) {
        return aGc.getAvgGcIntervalMs() == null ? "n/a" : duration(Math.round(aGc.getAvgGcIntervalMs()));
    }

    private static String allocationRate(TGcInfo aGc, Long aUptimeMs) {
        if (aGc.getAllocatedBytesTotal() == null || aUptimeMs == null || aUptimeMs <= 0) {
            return "n/a";
        }
        return MetricFormat.bytes(Math.round(aGc.getAllocatedBytesTotal() * 1000.0 / aUptimeMs)) + "/s";
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
        return seconds + "s";
    }

    private static String fmt(double aValue) {
        return aValue < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", aValue);
    }

    private static String pct(double aFraction) {
        return aFraction < 0 ? "n/a" : Math.round(aFraction * 100) + "%";
    }
}
