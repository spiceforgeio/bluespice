package dev.bluespice.core.sim;

import dev.bluespice.core.circuit.Circuit;

/**
 * Backend capable of opening simulation sessions for BlueSpice circuit graphs.
 *
 * <p>Implementations own any native libraries, worker processes, or thread pools needed by
 * their backend. Call {@link #close()} when the engine is no longer needed.
 */
public interface SimulationEngine extends AutoCloseable {
    /**
     * Opens a new session for the supplied circuit.
     *
     * @param circuit mutable circuit graph owned by the caller
     * @return session bound to the circuit
     */
    SimulationSession openSession(Circuit circuit);

    /**
     * Human-readable backend name, such as {@code ngspice} or {@code stub}.
     */
    String backendName();

    /**
     * Backend version string reported by the implementation.
     */
    String backendVersion();

    /**
     * Releases backend resources.
     */
    @Override
    void close();

    /**
     * Service-provider hook for creating engines from configuration.
     */
    interface Provider {
        /**
         * Creates an engine using the supplied configuration.
         */
        SimulationEngine create(EngineConfig config);

        /**
         * Provider name used for discovery and diagnostics.
         */
        String name();
    }
}
