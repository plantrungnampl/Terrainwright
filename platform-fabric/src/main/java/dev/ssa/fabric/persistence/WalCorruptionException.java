package dev.ssa.fabric.persistence;

public final class WalCorruptionException extends IllegalStateException {
    public WalCorruptionException(String message) {
        super(message);
    }

    public WalCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
