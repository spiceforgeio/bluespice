package dev.bluespice.core.sim;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Engine configuration shared by BlueSpice backends.
 *
 * @param nativeLibraryPath optional directory containing the native simulator library
 * @param enableXspice whether XSPICE support is expected from the native build
 * @param enableOpenMP whether OpenMP support is expected from the native build
 * @param maxWorkers maximum worker-process count, or {@code 0} for an implementation default
 * @param simulationTimeout maximum time to wait for a worker response
 * @param inProcessMode run the backend in the current JVM; ngspice supports only one active
 *     session in this mode because the shared library has process-global state
 */
public record EngineConfig(
        Path nativeLibraryPath,
        boolean enableXspice,
        boolean enableOpenMP,
        int maxWorkers,
        Duration simulationTimeout,
        boolean inProcessMode
) {
    public EngineConfig {
        if (maxWorkers < 0) {
            throw new IllegalArgumentException("maxWorkers must not be negative");
        }
        Objects.requireNonNull(simulationTimeout, "simulationTimeout");
        if (simulationTimeout.isNegative() || simulationTimeout.isZero()) {
            throw new IllegalArgumentException("simulationTimeout must be positive");
        }
    }

    /**
     * Returns production defaults for worker-process mode.
     */
    public static EngineConfig defaults() {
        return new EngineConfig(null, true, false, 0, Duration.ofSeconds(30), false);
    }
}
