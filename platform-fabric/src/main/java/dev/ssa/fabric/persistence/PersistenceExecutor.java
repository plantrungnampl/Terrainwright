package dev.ssa.fabric.persistence;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistenceExecutor implements AutoCloseable {
    private final ExecutorService executor;

    public PersistenceExecutor(String threadPrefix) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        AtomicInteger sequence = new AtomicInteger();
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadPrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public <T> CompletableFuture<T> submit(Callable<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.call();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("persistence executor did not terminate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing persistence executor", exception);
        }
    }
}
