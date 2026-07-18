package com.payneteasy.dcagent.operator.service.agent.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TSystemInfo;

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

    private GcLlmPayloadBuilder() {
    }

    static String build(String aAgentName, TSystemInfo aInfo) {
        TGcInfo gc = aInfo == null ? null : aInfo.getGc();
        StringBuilder b = new StringBuilder(1024);

        b.append("You are a JVM garbage-collection expert. Below are GC statistics and host context ")
         .append("collected from a running Java service. Explain in plain language what is happening ")
         .append("with GC, whether anything looks wrong, and what to check or change. Note that heap ")
         .append("memory is shared with other processes on the host, so consider host memory pressure ")
         .append("and swap, not just the JVM.\n\n");

        b.append("## Agent\n");
        b.append("name: ").append(aAgentName).append('\n');
        if (aInfo != null) {
            b.append("uptime: ").append(aInfo.getProcessCpuTimeNanos() < 0 ? "n/a"
                    : (aInfo.getProcessCpuTimeNanos() / 1_000_000_000) + "s of CPU").append('\n');
        }
        b.append('\n');

        if (gc == null || gc.getCollectionCount() <= 0) {
            b.append("## GC\nNo garbage collections have happened yet — nothing to analyse.\n");
            return b.toString();
        }

        b.append("## GC statistics\n");
        b.append("collections: ").append(gc.getCollectionCount()).append('\n');
        b.append("total pause: ").append(gc.getTotalPauseMs()).append(" ms\n");
        b.append("avg pause: ").append(fmt(gc.getAvgPauseMs())).append(" ms\n");
        b.append("max pause: ").append(gc.getMaxPauseMs()).append(" ms\n");
        b.append("last pause: ").append(gc.getLastPauseMs()).append(" ms\n");
        b.append("pauses over ").append(gc.getLongPauseThresholdMs()).append(" ms: ")
         .append(gc.getLongPauseCount()).append('\n');
        b.append("last cause: ").append(gc.getLastCause() == null ? "n/a" : gc.getLastCause()).append('\n');
        b.append("last action: ").append(gc.getLastAction() == null ? "n/a" : gc.getLastAction()).append('\n');
        b.append("live set after last GC: ").append(MetricFormat.bytes(gc.getLiveSetAfterBytes())).append('\n');
        b.append("live set after previous GC: ").append(MetricFormat.bytes(gc.getPrevLiveSetAfterBytes())).append('\n');
        b.append('\n');

        // aInfo is guaranteed non-null here: gc is non-null (we returned above otherwise), and gc is
        // only non-null when aInfo was non-null (see the gc assignment at the top of this method).
        b.append("## Heap / memory context\n");
        b.append("heap used: ").append(MetricFormat.bytes(aInfo.getHeapUsedBytes())).append('\n');
        b.append("heap committed: ").append(MetricFormat.bytes(aInfo.getHeapCommittedBytes())).append('\n');
        b.append("heap max: ").append(MetricFormat.bytes(aInfo.getHeapMaxBytes())).append('\n');
        b.append("non-heap used: ").append(MetricFormat.bytes(aInfo.getNonHeapUsedBytes())).append('\n');
        b.append("physical total: ").append(MetricFormat.bytes(aInfo.getPhysicalTotalBytes())).append('\n');
        b.append("physical free: ").append(MetricFormat.bytes(aInfo.getPhysicalFreeBytes())).append('\n');
        b.append("swap total: ").append(MetricFormat.bytes(aInfo.getSwapTotalBytes())).append('\n');
        b.append("swap free: ").append(MetricFormat.bytes(aInfo.getSwapFreeBytes())).append('\n');
        b.append('\n');

        b.append("## CPU context\n");
        b.append("processors: ").append(aInfo.getAvailableProcessors()).append('\n');
        b.append("load average: ").append(aInfo.getLoadAverage() < 0 ? "n/a"
                : String.format(Locale.ROOT, "%.2f", aInfo.getLoadAverage())).append('\n');
        b.append("system CPU: ").append(pct(aInfo.getSystemCpuLoad())).append('\n');
        b.append("process CPU: ").append(pct(aInfo.getProcessCpuLoad())).append('\n');
        b.append("threads: ").append(aInfo.getThreadCount()).append('\n');

        return b.toString();
    }

    private static String fmt(double aValue) {
        return aValue < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", aValue);
    }

    private static String pct(double aFraction) {
        return aFraction < 0 ? "n/a" : Math.round(aFraction * 100) + "%";
    }
}
