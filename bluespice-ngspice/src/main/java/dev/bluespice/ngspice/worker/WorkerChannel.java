package dev.bluespice.ngspice.worker;

import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.exception.SimulationTimeoutException;
import dev.bluespice.core.sim.EngineConfig;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client-side channel for a forked ngspice worker JVM.
 */
public class WorkerChannel implements AutoCloseable {
    private static final String READ_TIMED_OUT = "\u0000BLUESPICE_READ_TIMED_OUT";
    private final EngineConfig config;
    private final WorkerPool pool;
    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bluespice-ngspice-worker-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final Object writeLock = new Object();
    private final Object readLock = new Object();
    private final Object recoveryLock = new Object();
    private volatile boolean crashed;

    /**
     * Creates a standalone worker channel.
     */
    public WorkerChannel(EngineConfig config) {
        this(config, null);
    }

    WorkerChannel(EngineConfig config, WorkerPool pool) {
        this.config = Objects.requireNonNull(config, "config");
        this.pool = pool;
    }

    /**
     * Starts the worker process if needed.
     */
    public synchronized void start() {
        if (isAlive()) {
            return;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WorkerCrashException("failed to start ngspice worker", e);
        }
    }

    /**
     * Sends a command and waits for a response within the configured timeout.
     */
    public WorkerProtocol.Response send(WorkerProtocol.Command command) {
        Objects.requireNonNull(command, "command");
        try {
            writeCommand(command);
        } catch (WorkerCrashException e) {
            throw recoverAndThrow(e, failedProcess(e));
        }
        Process responseProcess = process;
        synchronized (readLock) {
            try {
                String response = readLineWithTimeout();
                if (response == READ_TIMED_OUT) {
                    handleTimeout(responseProcess);
                    throw new SimulationTimeoutException(
                            "Worker did not respond within " + config.simulationTimeout());
                }
                if (response == null) {
                    throw recoverAndThrow(new WorkerCrashException(
                            "ngspice worker terminated before sending a response"), responseProcess);
                }
                return WorkerProtocol.deserializeResponse(response);
            } catch (IOException e) {
                throw recoverAndThrow(
                        new WorkerCrashException("failed to communicate with ngspice worker", e),
                        responseProcess);
            }
        }
    }

    /**
     * Sends a command without waiting for a response.
     */
    public void sendWithoutResponse(WorkerProtocol.Command command) {
        try {
            writeCommand(command);
        } catch (WorkerCrashException e) {
            throw recoverAndThrow(e, failedProcess(e));
        }
    }

    /**
     * Returns whether the worker process is alive.
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Waits for worker process exit.
     */
    public synchronized boolean awaitExit(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (process == null) {
            return true;
        }
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops the worker and releases channel resources.
     */
    @Override
    public synchronized void close() {
        if (isAlive()) {
            try {
                writeCommand(new WorkerProtocol.Command.Exit());
                awaitExit(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                process.destroy();
            }
        }
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        readExecutor.shutdownNow();
    }

    private void writeCommand(WorkerProtocol.Command command) {
        Objects.requireNonNull(command, "command");
        synchronized (writeLock) {
            Process current = process;
            if (current == null || !current.isAlive()) {
                throw new DetectedWorkerCrashException("ngspice worker is not running", current);
            }
            try {
                writer.write(WorkerProtocol.serializeCommand(command));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new DetectedWorkerCrashException("failed to communicate with ngspice worker", e, current);
            }
        }
    }

    private String readLineWithTimeout() throws IOException {
        Future<String> future = readExecutor.submit(() -> reader.readLine());
        try {
            return future.get(Math.max(1L, config.simulationTimeout().toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return READ_TIMED_OUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading worker response", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("failed to read worker response", cause);
        }
    }

    private void handleTimeout(Process failedProcess) {
        try {
            sendRaw(new WorkerProtocol.Command.BgHalt());
            Thread.sleep(500);
        } catch (RuntimeException ignored) {
            // The worker is about to be replaced; preserve the timeout as the user-visible failure.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (pool == null) {
            terminateProcess(failedProcess);
            return;
        }
        synchronized (recoveryLock) {
            if (failedProcess != null && failedProcess != process) {
                return;
            }
            crashed = true;
            try {
                terminateProcess(failedProcess);
                WorkerChannel replacement = pool.replaceDeadWorker(this);
                adopt(replacement);
            } finally {
                crashed = false;
            }
        }
    }

    private void sendRaw(WorkerProtocol.Command command) {
        synchronized (writeLock) {
            try {
                if (writer != null) {
                    writer.write(WorkerProtocol.serializeCommand(command));
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ignored) {
                // The caller is already handling a failed worker.
            }
        }
    }

    private WorkerCrashException recoverAndThrow(WorkerCrashException cause, Process failedProcess) {
        if (pool == null) {
            return cause;
        }
        synchronized (recoveryLock) {
            if (failedProcess != null && failedProcess != process) {
                return cause;
            }
            crashed = true;
            try {
                terminateProcess(failedProcess);
                WorkerChannel replacement = pool.replaceDeadWorker(this);
                adopt(replacement);
            } finally {
                crashed = false;
            }
        }
        return cause;
    }

    private synchronized void adopt(WorkerChannel replacement) {
        this.process = replacement.process;
        this.writer = replacement.writer;
        this.reader = replacement.reader;
        replacement.process = null;
        replacement.writer = null;
        replacement.reader = null;
    }

    boolean crashed() {
        return crashed;
    }

    /**
     * Returns the underlying worker process for diagnostics and tests.
     */
    public Process process() {
        return process;
    }

    private Process failedProcess(WorkerCrashException exception) {
        if (exception instanceof DetectedWorkerCrashException detected) {
            return detected.failedProcess;
        }
        return process;
    }

    private void terminateProcess(Process failedProcess) {
        if (failedProcess == null) {
            return;
        }
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        failedProcess.destroy();
        try {
            if (!failedProcess.waitFor(500, TimeUnit.MILLISECONDS)) {
                failedProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failedProcess.destroyForcibly();
        }
    }

    private static final class DetectedWorkerCrashException extends WorkerCrashException {
        private final Process failedProcess;

        private DetectedWorkerCrashException(String message, Process failedProcess) {
            super(message);
            this.failedProcess = failedProcess;
        }

        private DetectedWorkerCrashException(String message, Throwable cause, Process failedProcess) {
            super(message, cause);
            this.failedProcess = failedProcess;
        }
    }

    private List<String> command() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Path nativeLibraryPath = config.nativeLibraryPath();
        if (nativeLibraryPath != null) {
            command.add("-Djna.library.path=" + nativeLibraryPath);
            command.add("-Dbluespice.ngspice.library.path=" + nativeLibraryPath);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(NgspiceWorker.class.getName());
        return command;
    }
}
