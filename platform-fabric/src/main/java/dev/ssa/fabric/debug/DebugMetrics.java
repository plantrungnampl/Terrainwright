package dev.ssa.fabric.debug;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Optional in-memory counters. Disabled instances perform no allocation on event recording paths. */
public final class DebugMetrics {
    public static final String ENABLE_PROPERTY = "smart_survival_architect.debugMetrics";
    private static final DebugMetrics GLOBAL = new DebugMetrics(Boolean.getBoolean(ENABLE_PROPERTY));

    private final boolean enabled;
    private final EnumMap<Counter, LongAdder> counters = new EnumMap<>(Counter.class);
    private final EnumMap<Timing, TimingAccumulator> timings = new EnumMap<>(Timing.class);
    private final ConcurrentHashMap<String, LongAdder> candidateRejections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> reconciliationOutcomes = new ConcurrentHashMap<>();

    private DebugMetrics(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            for (Counter counter : Counter.values()) {
                counters.put(counter, new LongAdder());
            }
            for (Timing timing : Timing.values()) {
                timings.put(timing, new TimingAccumulator());
            }
        }
    }

    public static DebugMetrics global() {
        return GLOBAL;
    }

    public static DebugMetrics enabled() {
        return new DebugMetrics(true);
    }

    public static DebugMetrics disabled() {
        return new DebugMetrics(false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void increment(Counter counter) {
        add(counter, 1);
    }

    public void add(Counter counter, long amount) {
        Objects.requireNonNull(counter, "counter");
        if (amount < 0) {
            throw new IllegalArgumentException("Debug metric increments must not be negative");
        }
        if (enabled) {
            counters.get(counter).add(amount);
        }
    }

    public void recordNanos(Timing timing, long elapsedNanos) {
        Objects.requireNonNull(timing, "timing");
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("Debug timing must not be negative");
        }
        if (enabled) {
            timings.get(timing).record(elapsedNanos);
        }
    }

    public void recordCandidateRejection(String reason) {
        recordNamed(candidateRejections, reason, "candidate rejection reason");
    }

    public void recordReconciliationOutcome(String outcome) {
        if (enabled) {
            counters.get(Counter.RECONCILIATION).increment();
        }
        recordNamed(reconciliationOutcomes, outcome, "reconciliation outcome");
    }

    public Snapshot snapshot() {
        if (!enabled) {
            return Snapshot.empty();
        }
        EnumMap<Counter, Long> counterSnapshot = new EnumMap<>(Counter.class);
        counters.forEach((counter, value) -> counterSnapshot.put(counter, value.sum()));
        EnumMap<Timing, TimingSnapshot> timingSnapshot = new EnumMap<>(Timing.class);
        timings.forEach((timing, value) -> timingSnapshot.put(timing, value.snapshot()));
        return new Snapshot(
                counterSnapshot,
                timingSnapshot,
                namedSnapshot(candidateRejections),
                namedSnapshot(reconciliationOutcomes));
    }

    private void recordNamed(ConcurrentHashMap<String, LongAdder> destination, String name, String label) {
        Objects.requireNonNull(name, label);
        if (name.isBlank() || name.length() > 160) {
            throw new IllegalArgumentException(label + " must contain 1 to 160 characters");
        }
        if (enabled) {
            destination.computeIfAbsent(name, ignored -> new LongAdder()).increment();
        }
    }

    private static Map<String, Long> namedSnapshot(ConcurrentHashMap<String, LongAdder> source) {
        Map<String, Long> snapshot = new HashMap<>();
        source.forEach((name, count) -> snapshot.put(name, count.sum()));
        return Map.copyOf(snapshot);
    }

    public enum Counter {
        GENERATION_REQUEST,
        GENERATION_SUCCESS,
        GENERATION_FAILURE,
        PATH_ATTEMPT,
        PATH_FAILURE,
        SCAFFOLD_BLOCK,
        MATERIAL_TRIP,
        CONFLICT,
        RECONCILIATION
    }

    public enum Timing {
        GENERATION,
        PATHFINDING
    }

    public record TimingSnapshot(long samples, long totalNanos, long maximumNanos) {
        public TimingSnapshot {
            if (samples < 0 || totalNanos < 0 || maximumNanos < 0) {
                throw new IllegalArgumentException("Debug timing snapshot values must not be negative");
            }
        }

        private static TimingSnapshot empty() {
            return new TimingSnapshot(0, 0, 0);
        }
    }

    public record Snapshot(
            Map<Counter, Long> counters,
            Map<Timing, TimingSnapshot> timings,
            Map<String, Long> candidateRejections,
            Map<String, Long> reconciliationOutcomes) {
        public Snapshot {
            counters = Map.copyOf(Objects.requireNonNull(counters, "counters"));
            timings = Map.copyOf(Objects.requireNonNull(timings, "timings"));
            candidateRejections = Map.copyOf(Objects.requireNonNull(candidateRejections, "candidateRejections"));
            reconciliationOutcomes = Map.copyOf(Objects.requireNonNull(
                    reconciliationOutcomes, "reconciliationOutcomes"));
        }

        public long counter(Counter counter) {
            return counters.getOrDefault(Objects.requireNonNull(counter, "counter"), 0L);
        }

        public TimingSnapshot timing(Timing timing) {
            return timings.getOrDefault(Objects.requireNonNull(timing, "timing"), TimingSnapshot.empty());
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private static final class TimingAccumulator {
        private final LongAdder samples = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAccumulator maximumNanos = new LongAccumulator(Long::max, 0);

        private void record(long elapsedNanos) {
            samples.increment();
            totalNanos.add(elapsedNanos);
            maximumNanos.accumulate(elapsedNanos);
        }

        private TimingSnapshot snapshot() {
            return new TimingSnapshot(samples.sum(), totalNanos.sum(), maximumNanos.get());
        }
    }
}
