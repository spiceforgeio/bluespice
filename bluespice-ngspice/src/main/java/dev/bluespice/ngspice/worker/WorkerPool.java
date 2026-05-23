package dev.bluespice.ngspice.worker;

import dev.bluespice.core.exception.TooManySessionsException;
import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

public final class WorkerPool implements AutoCloseable {
    private final EngineConfig config;
    private final int maxWorkers;
    private final ArrayDeque<WorkerChannel> idle = new ArrayDeque<>();
    private final Set<WorkerChannel> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object lock = new Object();
    private int workerCount;
    private boolean closed;

    public WorkerPool(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.maxWorkers = maxWorkers(config);
    }

    public WorkerChannel acquire() throws InterruptedException {
        WorkerChannel worker = takeIdleOrReserveSlot();
        if (worker != null) {
            return worker;
        }

        boolean success = false;
        WorkerChannel created = newWorker();
        try {
            created.start();
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("worker pool is closed");
                }
                active.add(created);
            }
            success = true;
            return created;
        } finally {
            if (!success) {
                created.close();
                forgetReservedSlot();
            }
        }
    }

    public void release(WorkerChannel worker) {
        Objects.requireNonNull(worker, "worker");

        synchronized (lock) {
            if (!active.remove(worker) && !closed) {
                return;
            }
            if (closed) {
                worker.close();
                return;
            }
        }

        if (!reset(worker)) {
            closeAndForget(worker);
            return;
        }

        synchronized (lock) {
            if (closed) {
                worker.close();
                return;
            }
            if (idle.size() < maxWorkers) {
                idle.addLast(worker);
                lock.notifyAll();
                return;
            }
        }
        closeAndForget(worker);
    }

    public WorkerChannel replaceDeadWorker(WorkerChannel dead) {
        Objects.requireNonNull(dead, "dead");
        WorkerChannel replacement = newWorker();
        try {
            replacement.start();
        } catch (RuntimeException e) {
            synchronized (lock) {
                active.remove(dead);
                if (workerCount > 0) {
                    workerCount--;
                }
                lock.notifyAll();
            }
            throw e;
        }

        synchronized (lock) {
            if (closed) {
                replacement.close();
                throw new WorkerCrashException("worker pool is closed");
            }
            return replacement;
        }
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        WorkerChannel[] workers;
        synchronized (lock) {
            closed = true;
            workers = new WorkerChannel[idle.size() + active.size()];
            int index = 0;
            for (WorkerChannel worker : idle) {
                workers[index++] = worker;
            }
            for (WorkerChannel worker : active) {
                workers[index++] = worker;
            }
            idle.clear();
            active.clear();
            workerCount = 0;
            lock.notifyAll();
        }
        for (WorkerChannel worker : workers) {
            worker.close();
        }
    }

    private WorkerChannel takeIdleOrReserveSlot() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("worker pool is closed");
                }
                WorkerChannel worker = idle.pollFirst();
                if (worker != null) {
                    active.add(worker);
                    return worker;
                }
                if (workerCount < maxWorkers) {
                    workerCount++;
                    return null;
                }
                if (config.inProcessMode()) {
                    throw new TooManySessionsException("in-process ngspice mode allows only one active session");
                }
                lock.wait();
            }
        }
    }

    private boolean reset(WorkerChannel worker) {
        try {
            WorkerProtocol.Response response = worker.send(new WorkerProtocol.Command.Reset());
            return response instanceof WorkerProtocol.Response.Ok;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private WorkerChannel newWorker() {
        if (config.inProcessMode()) {
            return new InProcessWorkerChannel(config);
        }
        return new WorkerChannel(config, this);
    }

    private void closeAndForget(WorkerChannel worker) {
        worker.close();
        forgetReservedSlot();
    }

    private void forgetReservedSlot() {
        synchronized (lock) {
            if (workerCount > 0) {
                workerCount--;
            }
            lock.notifyAll();
        }
    }

    private static int maxWorkers(EngineConfig config) {
        if (config.inProcessMode()) {
            return 1;
        }
        if (config.maxWorkers() > 0) {
            return config.maxWorkers();
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
