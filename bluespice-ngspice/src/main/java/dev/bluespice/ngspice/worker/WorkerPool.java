package dev.bluespice.ngspice.worker;

import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

public final class WorkerPool implements AutoCloseable {
    private final EngineConfig config;
    private final Semaphore permits;
    private final Set<WorkerChannel> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object lock = new Object();
    private volatile boolean closed;

    public WorkerPool(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.permits = new Semaphore(maxWorkers(config));
    }

    public WorkerChannel acquire() {
        if (closed) {
            throw new IllegalStateException("worker pool is closed");
        }
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("interrupted while waiting for ngspice worker", e);
        }
        boolean success = false;
        try {
            WorkerChannel worker = new WorkerChannel(config);
            worker.start();
            synchronized (lock) {
                if (closed) {
                    worker.close();
                    throw new IllegalStateException("worker pool is closed");
                }
                active.add(worker);
            }
            success = true;
            return worker;
        } finally {
            if (!success) {
                permits.release();
            }
        }
    }

    public void release(WorkerChannel worker) {
        Objects.requireNonNull(worker, "worker");
        synchronized (lock) {
            active.remove(worker);
        }
        try {
            worker.close();
        } finally {
            permits.release();
        }
    }

    @Override
    public void close() {
        WorkerChannel[] workers;
        synchronized (lock) {
            closed = true;
            workers = active.toArray(WorkerChannel[]::new);
            active.clear();
        }
        for (WorkerChannel worker : workers) {
            worker.close();
            permits.release();
        }
    }

    private static int maxWorkers(EngineConfig config) {
        if (config.maxWorkers() > 0) {
            return config.maxWorkers();
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
