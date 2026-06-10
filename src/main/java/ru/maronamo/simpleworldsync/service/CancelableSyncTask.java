package ru.maronamo.simpleworldsync.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class CancelableSyncTask {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<SyncResult> future;

    private CancelableSyncTask(Operation operation) {
        this.future = CompletableFuture.supplyAsync(() -> operation.run(this::isCancelled));
    }

    public static CancelableSyncTask start(Operation operation) {
        return new CancelableSyncTask(operation);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isDone() {
        return future.isDone();
    }

    public CompletableFuture<SyncResult> future() {
        return future;
    }

    @FunctionalInterface
    public interface Operation {
        SyncResult run(BooleanSupplier isCancelled);
    }
}
