package dev.bluespice.ngspice.worker;

import dev.bluespice.core.exception.WorkerCrashException;
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
import java.util.concurrent.TimeUnit;

public final class WorkerChannel implements AutoCloseable {
    private final EngineConfig config;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    public WorkerChannel(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

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

    public synchronized WorkerProtocol.Response send(WorkerProtocol.Command command) {
        Objects.requireNonNull(command, "command");
        if (!isAlive()) {
            throw new WorkerCrashException("ngspice worker is not running");
        }
        try {
            writer.write(WorkerProtocol.serializeCommand(command));
            writer.newLine();
            writer.flush();
            String response = reader.readLine();
            if (response == null) {
                throw new WorkerCrashException("ngspice worker terminated before sending a response");
            }
            return WorkerProtocol.deserializeResponse(response);
        } catch (IOException e) {
            throw new WorkerCrashException("failed to communicate with ngspice worker", e);
        }
    }

    public synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

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

    @Override
    public synchronized void close() {
        if (isAlive()) {
            try {
                send(new WorkerProtocol.Command.Exit());
                awaitExit(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                process.destroy();
            }
        }
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
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
