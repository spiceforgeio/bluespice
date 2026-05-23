package dev.bluespice.ngspice.worker;

import dev.bluespice.core.exception.SimulationTimeoutException;
import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class InProcessWorkerChannel extends WorkerChannel {
    private final EngineConfig config;
    private final ArrayBlockingQueue<Request> requests = new ArrayBlockingQueue<>(32);
    private volatile Thread workerThread;
    private volatile boolean closing;
    private volatile WorkerCrashException failure;

    InProcessWorkerChannel(EngineConfig config) {
        super(config);
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public synchronized void start() {
        if (isAlive()) {
            return;
        }
        closing = false;
        failure = null;
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        workerThread = new Thread(() -> runWorker(initialized), "bluespice-ngspice-in-process");
        workerThread.setDaemon(true);
        workerThread.start();
        try {
            initialized.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("interrupted while starting in-process ngspice worker", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new WorkerCrashException("failed to start in-process ngspice worker", cause);
        }
    }

    @Override
    public WorkerProtocol.Response send(WorkerProtocol.Command command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<WorkerProtocol.Response> response = new CompletableFuture<>();
        submit(new Request(command, response));
        try {
            return response.get(Math.max(1L, config.simulationTimeout().toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("interrupted while waiting for in-process ngspice worker", e);
        } catch (TimeoutException e) {
            sendWithoutResponse(new WorkerProtocol.Command.BgHalt());
            throw new SimulationTimeoutException("Worker did not respond within " + config.simulationTimeout(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WorkerCrashException workerCrash) {
                throw workerCrash;
            }
            throw new WorkerCrashException("in-process ngspice worker failed", cause);
        }
    }

    @Override
    public void sendWithoutResponse(WorkerProtocol.Command command) {
        submit(new Request(Objects.requireNonNull(command, "command"), null));
    }

    @Override
    public boolean isAlive() {
        Thread thread = workerThread;
        return thread != null && thread.isAlive() && failure == null;
    }

    @Override
    public boolean awaitExit(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        Thread thread = workerThread;
        if (thread == null) {
            return true;
        }
        try {
            thread.join(timeout.toMillis());
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (workerThread == null || closing) {
            return;
        }
        closing = true;
        try {
            send(new WorkerProtocol.Command.Exit());
        } catch (RuntimeException ignored) {
            // The worker may already have failed; close still drains the thread below.
        }
        if (!awaitExit(Duration.ofSeconds(2))) {
            workerThread.interrupt();
        }
    }

    private void submit(Request request) {
        WorkerCrashException currentFailure = failure;
        if (currentFailure != null) {
            throw currentFailure;
        }
        if (workerThread == null || !workerThread.isAlive()) {
            throw new WorkerCrashException("in-process ngspice worker is not running");
        }
        try {
            requests.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("interrupted while sending to in-process ngspice worker", e);
        }
    }

    private void runWorker(CompletableFuture<Void> initialized) {
        NgspiceWorker worker = new NgspiceWorker(true);
        Request activeRequest = null;
        try {
            if (config.nativeLibraryPath() != null) {
                String path = config.nativeLibraryPath().toString();
                System.setProperty("jna.library.path", path);
                System.setProperty("bluespice.ngspice.library.path", path);
            }
            worker.initialize();
            initialized.complete(null);

            boolean running = true;
            while (running) {
                WorkerProtocol.Response finished = worker.finishActiveTransientIfDone();
                if (finished != null) {
                    complete(activeRequest, finished);
                    activeRequest = null;
                    continue;
                }

                Request request = pollRequest(worker.hasActiveTransient());
                if (request == null) {
                    worker.waitForActiveTransient(100);
                    continue;
                }

                WorkerProtocol.Response response = worker.handle(request.command());
                if (request.command() instanceof WorkerProtocol.Command.Exit) {
                    complete(request, response);
                    running = false;
                } else if (response != null) {
                    complete(request, response);
                } else if (request.response() != null) {
                    activeRequest = request;
                }
            }
        } catch (Throwable t) {
            WorkerCrashException crash = t instanceof WorkerCrashException workerCrash
                    ? workerCrash
                    : new WorkerCrashException("in-process ngspice worker failed", t);
            failure = crash;
            initialized.completeExceptionally(crash);
            fail(activeRequest, crash);
            drainFailed(crash);
        }
    }

    private Request pollRequest(boolean activeTransient) throws InterruptedException {
        if (activeTransient) {
            return requests.poll(100, TimeUnit.MILLISECONDS);
        }
        return requests.take();
    }

    private void drainFailed(WorkerCrashException crash) {
        Request request;
        while ((request = requests.poll()) != null) {
            fail(request, crash);
        }
    }

    private void complete(Request request, WorkerProtocol.Response response) {
        if (request != null && request.response() != null) {
            request.response().complete(response);
        }
    }

    private void fail(Request request, WorkerCrashException crash) {
        if (request != null && request.response() != null) {
            request.response().completeExceptionally(crash);
        }
    }

    private record Request(
            WorkerProtocol.Command command,
            CompletableFuture<WorkerProtocol.Response> response
    ) {}
}
