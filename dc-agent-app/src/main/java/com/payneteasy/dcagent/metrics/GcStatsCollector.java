package com.payneteasy.dcagent.metrics;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Subscribes to {@link GarbageCollectionNotificationInfo} JMX notifications and keeps a running
 * summary of GC behaviour: total collections, longest pause, a decaying "recent" pause figure, the
 * last cause, and the live-set size after the most recent collection (heap used right after a GC —
 * the closest single number to "how much memory the app actually holds").
 *
 * <p>Deliberately does NOT keep per-event history (that would grow unbounded and is what a log or
 * GCToolKit is for). It keeps only the aggregates the operator needs to render a one-line verdict.
 * All fields are updated from the (single) GC notification thread and read from request threads, so
 * they are stored in atomics / volatiles.
 *
 * <p>Note: the JMX bean does NOT expose the user/sys/real CPU split from the unified GC log, so the
 * "wall-clock &gt; cpu-time" heuristic cannot be reproduced here. The operator's verdict works from
 * pause magnitude and live-set trend instead.
 */
public class GcStatsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(GcStatsCollector.class);

    private final LongAdder  totalCollections    = new LongAdder();
    private final LongAdder  totalPauseMs         = new LongAdder();
    private final AtomicLong maxPauseMs           = new AtomicLong(0);
    private final AtomicLong lastPauseMs          = new AtomicLong(-1);
    private final AtomicLong lastGcEpochMs        = new AtomicLong(-1);
    private final AtomicLong liveSetAfterBytes    = new AtomicLong(-1); // heap used right after last GC
    private final AtomicLong prevLiveSetAfterBytes = new AtomicLong(-1); // one before that
    private final AtomicLong longPauseCount       = new AtomicLong(0);  // pauses over LONG_PAUSE_MS

    private volatile String  lastCause  = null;
    private volatile String  lastAction = null;

    /** A pause at/over this is "long" and gets counted separately for the verdict. */
    private static final long LONG_PAUSE_MS = 200;

    /** Install listeners on every GC MXBean. Call once at startup. */
    public void install() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener((notification, handback) -> {
                    if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                            .equals(notification.getType())) {
                        onGc(GarbageCollectionNotificationInfo.from(
                                (CompositeData) notification.getUserData()));
                    }
                }, null, null);
            }
        }
        LOG.info("GC stats collector installed on {} collectors",
                ManagementFactory.getGarbageCollectorMXBeans().size());
    }

    private void onGc(GarbageCollectionNotificationInfo info) {
        try {
            GcInfo gc      = info.getGcInfo();
            long   pauseMs = gc.getDuration();

            totalCollections.increment();
            totalPauseMs.add(pauseMs);
            lastPauseMs.set(pauseMs);
            lastGcEpochMs.set(System.currentTimeMillis());
            lastCause  = info.getGcCause();
            lastAction = info.getGcAction();

            maxPauseMs.accumulateAndGet(pauseMs, Math::max);
            if (pauseMs >= LONG_PAUSE_MS) {
                longPauseCount.incrementAndGet();
            }

            long usedAfter = sumUsed(gc.getMemoryUsageAfterGc());
            prevLiveSetAfterBytes.set(liveSetAfterBytes.get());
            liveSetAfterBytes.set(usedAfter);
        } catch (Exception e) {
            LOG.debug("Cannot process GC notification", e);
        }
    }

    private static long sumUsed(Map<String, MemoryUsage> pools) {
        long sum = 0;
        for (MemoryUsage u : pools.values()) {
            if (u != null && u.getUsed() > 0) {
                sum += u.getUsed();
            }
        }
        return sum;
    }

    /** Snapshot the current aggregates. Cheap; safe to call per request. */
    public TGcInfo snapshot() {
        long count = totalCollections.sum();
        long total = totalPauseMs.sum();
        return TGcInfo.builder()
                .collectionCount(count)
                .totalPauseMs(total)
                .avgPauseMs(count > 0 ? (double) total / count : -1)
                .maxPauseMs(maxPauseMs.get())
                .lastPauseMs(lastPauseMs.get())
                .lastGcEpochMs(lastGcEpochMs.get())
                .longPauseCount(longPauseCount.get())
                .longPauseThresholdMs(LONG_PAUSE_MS)
                .liveSetAfterBytes(liveSetAfterBytes.get())
                .prevLiveSetAfterBytes(prevLiveSetAfterBytes.get())
                .lastCause(lastCause)
                .lastAction(lastAction)
                .build();
    }
}
