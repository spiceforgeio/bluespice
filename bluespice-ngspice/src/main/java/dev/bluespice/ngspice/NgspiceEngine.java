package dev.bluespice.ngspice;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.SimulationEngine;
import dev.bluespice.core.sim.SimulationSession;

public final class NgspiceEngine implements SimulationEngine {
    private final EngineConfig config;

    public NgspiceEngine(EngineConfig config) {
        this.config = config;
    }

    public static NgspiceEngine load() {
        return new NgspiceEngine(EngineConfig.defaults());
    }

    public EngineConfig config() {
        return config;
    }

    @Override
    public SimulationSession openSession(Circuit circuit) {
        throw new UnsupportedOperationException("NgspiceEngine.openSession() will be wired to WorkerChannel in Phase 3");
    }

    @Override
    public String backendName() {
        return "ngspice";
    }

    @Override
    public String backendVersion() {
        return "unavailable";
    }

    @Override
    public void close() {
    }
}
