package dev.ssa.fabric.client.spike.preview;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

public final class PreviewRenderMetrics {
    private static final int SAMPLE_CAPACITY = 120;

    private final long[] durationMicros = new long[SAMPLE_CAPACITY];
    private final long[] allocatedBytes = new long[SAMPLE_CAPACITY];
    private final com.sun.management.ThreadMXBean allocationBean;
    private volatile boolean collecting;
    private volatile int sampleCount;

    PreviewRenderMetrics() {
        java.lang.management.ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (threadBean instanceof com.sun.management.ThreadMXBean extended
                && extended.isThreadAllocatedMemorySupported()) {
            if (!extended.isThreadAllocatedMemoryEnabled()) {
                extended.setThreadAllocatedMemoryEnabled(true);
            }
            allocationBean = extended;
        } else {
            allocationBean = null;
        }
    }

    void start() {
        sampleCount = 0;
        collecting = true;
    }

    Profile stop() {
        collecting = false;
        int count = sampleCount;
        long[] durations = Arrays.copyOf(durationMicros, count);
        long[] allocations = Arrays.copyOf(allocatedBytes, count);
        Arrays.sort(durations);
        Arrays.sort(allocations);
        return new Profile(
                count,
                percentile(durations, 0.50),
                percentile(durations, 0.95),
                maximum(durations),
                percentile(allocations, 0.95),
                maximum(allocations),
                allocationBean != null);
    }

    boolean collecting() {
        return collecting;
    }

    int sampleCount() {
        return sampleCount;
    }

    long currentThreadAllocatedBytes() {
        if (allocationBean == null) {
            return 0;
        }
        return allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    void record(long durationNanos, long allocatedBefore) {
        int index = sampleCount;
        if (!collecting || index >= SAMPLE_CAPACITY) {
            return;
        }
        durationMicros[index] = durationNanos / 1_000;
        allocatedBytes[index] = allocationBean == null
                ? 0
                : Math.max(0, currentThreadAllocatedBytes() - allocatedBefore);
        sampleCount = index + 1;
    }

    private static long percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1);
        return sorted[index];
    }

    private static long maximum(long[] sorted) {
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1];
    }

    public record Profile(
            int sampleCount,
            long p50Micros,
            long p95Micros,
            long maxMicros,
            long p95AllocatedBytes,
            long maxAllocatedBytes,
            boolean allocationSupported) {}
}
