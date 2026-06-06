package dev.bluespice.ngspice;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Topology;
import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.SimulationEngine;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.ngspice.netlist.NetlistBuilder;
import dev.bluespice.ngspice.worker.WorkerChannel;
import dev.bluespice.ngspice.worker.WorkerPool;
import dev.bluespice.ngspice.worker.WorkerProtocol;
import java.util.Objects;

/**
 * {@link SimulationEngine} backed by ngspice 44 through JNA.
 *
 * <p>Default mode uses a pool of isolated worker JVMs so multiple sessions can run concurrently
 * without sharing ngspice process-global state. {@link EngineConfig#inProcessMode()} is available
 * for constrained environments, but it allows only one active session. Upstream ngspice has no GPU
 * acceleration path as of 2026, so BlueSpice performs all ngspice-backed simulation on CPU.
 */
public final class NgspiceEngine implements SimulationEngine {
    private final EngineConfig config;
    private final WorkerPool workerPool;
    private final NetlistBuilder netlistBuilder = new NetlistBuilder();

    /**
     * Creates an engine with explicit configuration.
     */
    public NgspiceEngine(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.workerPool = new WorkerPool(config);
    }

    /**
     * Loads ngspice with {@link EngineConfig#defaults()}.
     */
    public static NgspiceEngine load() {
        return load(EngineConfig.defaults());
    }

    /**
     * Loads ngspice with explicit configuration.
     */
    public static NgspiceEngine load(EngineConfig config) {
        return new NgspiceEngine(config);
    }

    /**
     * Returns the engine configuration.
     */
    public EngineConfig config() {
        return config;
    }

    /**
     * Opens a session, splitting disconnected circuit groups across sub-sessions when possible.
     */
    @Override
    public SimulationSession openSession(Circuit circuit) {
        Objects.requireNonNull(circuit, "circuit");
        if (Topology.isDisconnected(circuit)) {
            int partCount = Topology.split(circuit).size();
            return new SplitSession(circuit, this::openSingleSession, partCount <= effectiveMaxWorkers());
        }
        return openSingleSession(circuit);
    }

    NgspiceSession openSingleSession(Circuit circuit) {
        Objects.requireNonNull(circuit, "circuit");
        WorkerChannel worker;
        try {
            worker = workerPool.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("interrupted while waiting for ngspice worker", e);
        }
        try {
            NetlistBuilder.BuiltNetlist netlist = netlistBuilder.buildDetailed(circuit);
            WorkerProtocol.Response response = worker.send(new WorkerProtocol.Command.LoadCircuit(
                    netlist.lines(), netlist.nodeNames(), netlist.branchComponents()));
            if (!(response instanceof WorkerProtocol.Response.Ok)) {
                if (response instanceof WorkerProtocol.Response.Error error) {
                    throw invalidNetlist(error.message());
                }
                throw new IllegalStateException("LOAD_CIRCUIT returned unexpected response: "
                        + response.getClass().getSimpleName());
            }
            return new NgspiceSession(circuit, worker, () -> workerPool.release(worker), false);
        } catch (RuntimeException e) {
            workerPool.release(worker);
            throw e;
        }
    }

    /**
     * Returns the backend name {@code ngspice}.
     */
    @Override
    public String backendName() {
        return "ngspice";
    }

    /**
     * Returns the bundled/tested ngspice major version.
     */
    @Override
    public String backendVersion() {
        return "44";
    }

    /**
     * Closes the worker pool and all idle or active workers owned by the engine.
     */
    @Override
    public void close() {
        workerPool.close();
    }

    private IllegalArgumentException invalidNetlist(String message) {
        String normalized = message.startsWith("invalid netlist:")
                ? message.substring("invalid netlist:".length()).trim()
                : message;
        return new IllegalArgumentException("Invalid netlist: " + normalized);
    }

    private int effectiveMaxWorkers() {
        if (config.inProcessMode()) {
            return 1;
        }
        if (config.maxWorkers() > 0) {
            return config.maxWorkers();
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
