package com.payneteasy.dcagent.metrics;

import org.junit.Test;

import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GcStatsCollector#sumUsed} must count HEAP pools only. Before the fix it summed every pool the
 * JMX notification carries, so "live set" was inflated by Metaspace + all three CodeHeaps (~45 MB on a
 * real snapshot). Here we feed a map shaped exactly like a HotSpot {@code GcInfo.getMemoryUsageAfterGc()}
 * and assert the non-heap pools are excluded by name.
 */
public class GcStatsCollectorTest {

    private static MemoryUsage used(long aUsedBytes) {
        // init/max undefined (-1); committed == used keeps the MemoryUsage invariants happy.
        return new MemoryUsage(-1, aUsedBytes, aUsedBytes, -1);
    }

    @Test
    public void sumUsedCountsHeapPoolsOnly() {
        Map<String, MemoryUsage> pools = new LinkedHashMap<>();
        pools.put("Eden Space",                    used(10_000_000));
        pools.put("Survivor Space",                used(1_000_000));
        pools.put("Tenured Gen",                   used(20_000_000));
        pools.put("Metaspace",                     used(25_000_000));   // non-heap — must be excluded
        pools.put("Compressed Class Space",        used(3_000_000));    // non-heap — must be excluded
        pools.put("CodeHeap 'profiled nmethods'",  used(8_000_000));    // non-heap — must be excluded

        Set<String> heap = Set.of("Eden Space", "Survivor Space", "Tenured Gen");

        assertThat(GcStatsCollector.sumUsed(pools, heap)).isEqualTo(31_000_000L);
    }

    @Test
    public void sumUsedIncludesEmptyEden() {
        // The old ">0" guard hid a fully-collected eden. An empty heap pool must still be a valid,
        // counted member (contributes 0) — not silently skipped.
        Map<String, MemoryUsage> pools = new LinkedHashMap<>();
        pools.put("Eden Space",  used(0));
        pools.put("Tenured Gen", used(5_000_000));

        assertThat(GcStatsCollector.sumUsed(pools, Set.of("Eden Space", "Tenured Gen")))
                .isEqualTo(5_000_000L);
    }

    @Test
    public void sumUsedIgnoresPoolsOutsideHeapSet() {
        Map<String, MemoryUsage> pools = new LinkedHashMap<>();
        pools.put("Metaspace", used(25_000_000));

        assertThat(GcStatsCollector.sumUsed(pools, Set.of("Eden Space", "Tenured Gen"))).isZero();
    }
}
