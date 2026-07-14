package com.payneteasy.dcagent.metrics;

import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Periodically samples system/process CPU load. {@code getCpuLoad()/getProcessCpuLoad()} compute a
 * delta between successive calls (the first call returns NaN/-1), so they must be polled on a timer
 * rather than read per request. The latest values are cached in volatile fields; -1 means
 * "not sampled yet / unavailable".
 */
public class CpuLoadSampler {

    private static final Logger LOG = LoggerFactory.getLogger(CpuLoadSampler.class);

    private final OperatingSystemMXBean osBean; // nullable on non-HotSpot JVMs

    private volatile double systemCpuLoad  = -1;
    private volatile double processCpuLoad = -1;

    public CpuLoadSampler(OperatingSystemMXBean aOsBean) {
        osBean = aOsBean;
    }

    /** Start the daemon sampler. No-op if the com.sun OS MXBean is unavailable. */
    public void start(long aIntervalMs) {
        if (osBean == null) {
            LOG.info("com.sun OperatingSystemMXBean unavailable; CPU load will report -1");
            return;
        }
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "cpu-load-sampler");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(threads);
        executor.scheduleWithFixedDelay(this::sample, 0, Math.max(500, aIntervalMs), MILLISECONDS);
    }

    private void sample() {
        try {
            systemCpuLoad  = osBean.getCpuLoad();
            processCpuLoad = osBean.getProcessCpuLoad();
        } catch (Exception e) {
            LOG.debug("Cannot sample CPU load", e);
        }
    }

    public double getSystemCpuLoad() {
        return systemCpuLoad;
    }

    public double getProcessCpuLoad() {
        return processCpuLoad;
    }
}
