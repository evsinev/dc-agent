package com.payneteasy.dcagent.metrics;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TGcInfo;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.TLiveSetSample;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    private final AtomicBoolean installed         = new AtomicBoolean(false);

    private final LongAdder  totalCollections    = new LongAdder();
    private final LongAdder  totalPauseMs         = new LongAdder();
    private final AtomicLong maxPauseMs           = new AtomicLong(0);
    private final AtomicLong lastPauseMs          = new AtomicLong(-1);
    private final AtomicLong lastGcEpochMs        = new AtomicLong(-1);
    private final AtomicLong longPauseCount       = new AtomicLong(0);  // pauses over LONG_PAUSE_MS
    private final AtomicLong subMsPauseCount      = new AtomicLong(0);  // collections JMX reported as 0 ms

    /** Live set after the last GC and the one before it, updated as one indivisible pair (see {@link LiveSet}). */
    private final AtomicReference<LiveSet> liveSet = new AtomicReference<>(new LiveSet(-1, -1, -1));

    private volatile String  lastCause  = null;
    private volatile String  lastAction = null;

    // Heap-pool identity, resolved once at install(). Lets sumUsed() count heap-only and lets later
    // stages address eden/old by name. edenPoolName/oldPoolName are null on ZGC/Shenandoah.
    private volatile Set<String> heapPoolNames = Set.of();
    private volatile String      edenPoolName;
    private volatile String      oldPoolName;
    private volatile long        oldGenMaxBytes = -1;                 // old-gen capacity, once at install()
    private volatile List<String> collectorNames = List.of();         // which collectors are running

    // Allocation + interval between collections.
    private final LongAdder  allocatedBytesTotal = new LongAdder();
    private final AtomicLong lastEdenAfterBytes   = new AtomicLong(0);
    private final AtomicLong firstGcEpochMs       = new AtomicLong(-1);
    private final AtomicLong lastGcIntervalMs     = new AtomicLong(-1);

    // Full-GC series + old gen.
    private final AtomicLong                fullGcCount            = new AtomicLong(0);
    private final AtomicReference<LiveSet>  fullLiveSet            = new AtomicReference<>(new LiveSet(-1, -1, -1));
    private final AtomicLong                oldGenUsedAfterGcBytes = new AtomicLong(-1);
    private static final int OLD_GEN_SAMPLES_MAX = 32;
    private final Deque<TLiveSetSample> oldGenSamples = new ArrayDeque<>(OLD_GEN_SAMPLES_MAX);

    // Longest pause within the last sliding hour (two rotating hourly buckets, no memory growth).
    private final RecentMaxPause recentMaxPause = new RecentMaxPause();

    /** A pause at/over this is "long" and gets counted separately for the verdict. */
    private static final long LONG_PAUSE_MS = 200;

    /** Heap used right after a GC, the one before it, and the wall-clock of the latest — one immutable record. */
    private record LiveSet(long lastBytes, long prevBytes, long lastEpochMs) {
    }

    /**
     * Longest GC pause within a sliding hour, kept in two rotating buckets (current hour + previous) so
     * memory never grows. Reported value expires once two full hours pass with no pause. All access is
     * synchronized: pauses arrive on the GC thread, the snapshot is read on a request thread.
     */
    private static final class RecentMaxPause {
        private long hour     = Long.MIN_VALUE;   // hour index of the current bucket
        private long current  = -1;
        private long previous = -1;

        synchronized void record(long aPauseMs, long aNowMs) {
            roll(aNowMs / 3_600_000L);
            current = Math.max(current, aPauseMs);
        }

        synchronized long max(long aNowMs) {
            roll(aNowMs / 3_600_000L);
            return Math.max(current, previous);
        }

        private void roll(long aHour) {
            if (aHour == hour) {
                return;
            }
            previous = aHour == hour + 1 ? current : -1;   // a 2+ hour gap leaves both buckets stale
            current  = -1;
            hour     = aHour;
        }
    }

    /** Install listeners on every GC MXBean. Idempotent: a second call is a no-op (no duplicate listeners). */
    public void install() {
        if (!installed.compareAndSet(false, true)) {
            LOG.debug("GC stats collector already installed; ignoring repeat install()");
            return;
        }
        resolvePools();
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            names.add(bean.getName());
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
        collectorNames = List.copyOf(names);
        LOG.info("GC stats collector installed on {} collectors: {}", names.size(), collectorNames);
    }

    /** Classify the memory pools once: which are heap, and which are eden / old (nullable). */
    private void resolvePools() {
        Set<String> heap = new HashSet<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            String name = pool.getName();
            heap.add(name);
            if (name.contains("Eden")) {
                edenPoolName = name;
            } else if (name.contains("Old") || name.contains("Tenured")) {
                oldPoolName = name;
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    oldGenMaxBytes = usage.getMax();
                }
            }
        }
        heapPoolNames = Set.copyOf(heap);
        LOG.info("Heap pools: {} (eden={}, old={}, oldMax={})",
                heapPoolNames, edenPoolName, oldPoolName, oldGenMaxBytes);
    }

    private void onGc(GarbageCollectionNotificationInfo info) {
        try {
            GcInfo gc      = info.getGcInfo();
            long   pauseMs = gc.getDuration();
            long   now     = System.currentTimeMillis();

            totalCollections.increment();
            totalPauseMs.add(pauseMs);
            lastPauseMs.set(pauseMs);
            lastCause  = info.getGcCause();
            lastAction = info.getGcAction();

            // Interval between collections: the gap from the previous GC, captured before we overwrite it.
            long prevEpoch = lastGcEpochMs.getAndSet(now);
            if (prevEpoch >= 0) {
                lastGcIntervalMs.set(now - prevEpoch);
            }
            firstGcEpochMs.compareAndSet(-1, now);

            maxPauseMs.accumulateAndGet(pauseMs, Math::max);
            recentMaxPause.record(pauseMs, now);
            if (pauseMs >= LONG_PAUSE_MS) {
                longPauseCount.incrementAndGet();
            }
            if (pauseMs == 0) {
                subMsPauseCount.incrementAndGet();   // JMX resolution is 1 ms; a real sub-ms pause reads as 0
            }

            // Allocation since the previous GC ≈ eden growth between collections.
            if (edenPoolName != null) {
                long edenBefore = usedOf(gc.getMemoryUsageBeforeGc(), edenPoolName);
                allocatedBytesTotal.add(Math.max(0, edenBefore - lastEdenAfterBytes.get()));
                lastEdenAfterBytes.set(usedOf(gc.getMemoryUsageAfterGc(), edenPoolName));
            }

            // Live set — heap only, updated as one indivisible pair.
            long usedAfter = sumUsed(gc.getMemoryUsageAfterGc(), heapPoolNames);
            liveSet.updateAndGet(old -> new LiveSet(usedAfter, old.lastBytes(), now));

            // Full-GC series: the meaningful leak signal is old gen after a full GC on a long base.
            boolean major = info.getGcAction() != null && info.getGcAction().contains("major");
            if (major) {
                fullGcCount.incrementAndGet();
                fullLiveSet.updateAndGet(old -> new LiveSet(usedAfter, old.lastBytes(), now));
            }

            // Old-gen after any GC + a bounded time series for real-window growth math.
            if (oldPoolName != null) {
                long oldUsed = usedOf(gc.getMemoryUsageAfterGc(), oldPoolName);
                oldGenUsedAfterGcBytes.set(oldUsed);
                recordOldGenSample(now, oldUsed);
            }
        } catch (Exception e) {
            LOG.debug("Cannot process GC notification", e);
        }
    }

    /** Sum heap-pool usage only. Non-heap pools (Metaspace, CodeHeap, …) are excluded by name. */
    static long sumUsed(Map<String, MemoryUsage> aPools, Set<String> aHeapPoolNames) {
        long sum = 0;
        for (Map.Entry<String, MemoryUsage> entry : aPools.entrySet()) {
            MemoryUsage usage = entry.getValue();
            if (usage != null && aHeapPoolNames.contains(entry.getKey())) {
                sum += usage.getUsed();
            }
        }
        return sum;
    }

    /** Used bytes of one named pool, or 0 when the pool is absent from the map. */
    private static long usedOf(Map<String, MemoryUsage> aPools, String aPoolName) {
        MemoryUsage usage = aPools.get(aPoolName);
        return usage != null ? usage.getUsed() : 0;
    }

    private void recordOldGenSample(long aEpochMs, long aUsedBytes) {
        synchronized (oldGenSamples) {
            if (oldGenSamples.size() >= OLD_GEN_SAMPLES_MAX) {
                oldGenSamples.removeFirst();
            }
            oldGenSamples.addLast(TLiveSetSample.builder().epochMs(aEpochMs).usedBytes(aUsedBytes).build());
        }
    }

    /** Snapshot the current aggregates. Cheap; safe to call per request. */
    public TGcInfo snapshot() {
        long    count = totalCollections.sum();
        long    total = totalPauseMs.sum();
        LiveSet last  = liveSet.get();
        LiveSet full  = fullLiveSet.get();
        long    fc    = fullGcCount.get();
        long    recent = recentMaxPause.max(System.currentTimeMillis());
        return TGcInfo.builder()
                .collectionCount(count)
                .totalPauseMs(total)
                .avgPauseMs(count > 0 ? (double) total / count : -1)
                .maxPauseMs(maxPauseMs.get())
                .lastPauseMs(lastPauseMs.get())
                .lastGcEpochMs(lastGcEpochMs.get())
                .longPauseCount(longPauseCount.get())
                .longPauseThresholdMs(LONG_PAUSE_MS)
                .liveSetAfterBytes(last.lastBytes())
                .prevLiveSetAfterBytes(last.prevBytes())
                .lastCause(lastCause)
                .lastAction(lastAction)
                .collectorNames(collectorNames)
                .allocatedBytesTotal(edenPoolName != null ? allocatedBytesTotal.sum() : null)
                .firstGcEpochMs(firstGcEpochMs.get() >= 0 ? firstGcEpochMs.get() : null)
                .lastGcIntervalMs(lastGcIntervalMs.get() >= 0 ? lastGcIntervalMs.get() : null)
                .avgGcIntervalMs(avgGcIntervalMs(count))
                .fullGcCount(fc)
                .liveSetAfterFullGcBytes(fc > 0 ? full.lastBytes() : null)
                .prevLiveSetAfterFullGcBytes(fc > 1 ? full.prevBytes() : null)
                .oldGenUsedAfterGcBytes(oldPoolName != null && oldGenUsedAfterGcBytes.get() >= 0
                        ? oldGenUsedAfterGcBytes.get() : null)
                .oldGenMaxBytes(oldPoolName != null && oldGenMaxBytes >= 0 ? oldGenMaxBytes : null)
                .oldGenSamples(oldPoolName != null ? copyOldGenSamples() : null)
                .maxPauseRecentMs(recent >= 0 ? recent : null)
                .subMsPauseCount(subMsPauseCount.get())
                .build();
    }

    /** Mean gap between collections over the whole observed window, or null before the second GC. */
    private Double avgGcIntervalMs(long aCount) {
        long first = firstGcEpochMs.get();
        long lastEpoch = lastGcEpochMs.get();
        if (aCount > 1 && first >= 0 && lastEpoch >= first) {
            return (double) (lastEpoch - first) / (aCount - 1);
        }
        return null;
    }

    private List<TLiveSetSample> copyOldGenSamples() {
        synchronized (oldGenSamples) {
            return new ArrayList<>(oldGenSamples);
        }
    }
}
