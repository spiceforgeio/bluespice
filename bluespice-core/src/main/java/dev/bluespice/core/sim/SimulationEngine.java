package dev.bluespice.core.sim;

import dev.bluespice.core.circuit.Circuit;

public interface SimulationEngine extends AutoCloseable {
    SimulationSession openSession(Circuit circuit);

    String backendName();

    String backendVersion();

    @Override
    void close();

    interface Provider {
        SimulationEngine create(EngineConfig config);

        String name();
    }
}
