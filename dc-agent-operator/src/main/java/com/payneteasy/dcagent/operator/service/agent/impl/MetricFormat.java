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

    /**
     * Human-readable byte size, or {@code "n/a"} for a negative sentinel. Uses binary (1024) units with
     * the correct IEC names — {@code KiB/MiB/GiB/…} — because the divisor is 1024, not 1000; labelling
     * a 1024-divided value "MB" is what made {@code -Xmx128m} read as an unfamiliar "123.8 MB".
     */
    static String bytes(long aBytes) {
        if (aBytes < 0) {
            return "n/a";
        }
        if (aBytes < 1024) {
            return aBytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = aBytes;
        int    unit  = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
