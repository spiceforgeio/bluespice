package dev.bluespice.core.sim;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

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

    public static EngineConfig defaults() {
        return new EngineConfig(null, true, false, 0, Duration.ofSeconds(30), false);
    }
}
