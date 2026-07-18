package com.payneteasy.dcagent.operator.service.agent.impl;

import java.util.Locale;

/**
 * Shared metric formatting helpers used by {@link AgentMetricsMapper} and the GC diagnostics classes
 * ({@link GcDoctor}, {@link GcLlmPayloadBuilder}). Kept in one place so the byte-scale rendering is
 * defined once rather than copied per class.
 */
final class MetricFormat {

    private MetricFormat() {
    }

    /** Human-readable byte size (B/KB/MB/GB/TB/PB), or {@code "n/a"} for a negative sentinel. */
    static String bytes(long aBytes) {
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
}
