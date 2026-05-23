package dev.bluespice.ngspice;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.SimulationEngine;
import dev.bluespice.ngspice.netlist.NetlistBuilder;
import dev.bluespice.ngspice.worker.WorkerChannel;
import dev.bluespice.ngspice.worker.WorkerPool;
import dev.bluespice.ngspice.worker.WorkerProtocol;
import java.util.Objects;

public final class NgspiceEngine implements SimulationEngine {
    private final EngineConfig config;
    private final WorkerPool workerPool;
    private final NetlistBuilder netlistBuilder = new NetlistBuilder();

    public NgspiceEngine(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.workerPool = new WorkerPool(config);
    }

    public static NgspiceEngine load() {
        return load(EngineConfig.defaults());
    }

    public static NgspiceEngine load(EngineConfig config) {
        return new NgspiceEngine(config);
    }

    public EngineConfig config() {
        return config;
    }

    @Override
    public NgspiceSession openSession(Circuit circuit) {
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
                    throw new IllegalStateException("LOAD_CIRCUIT failed: " + error.message());
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

    @Override
    public String backendName() {
        return "ngspice";
    }

    @Override
    public String backendVersion() {
        return "44";
    }

    @Override
    public void close() {
        workerPool.close();
    }
}
