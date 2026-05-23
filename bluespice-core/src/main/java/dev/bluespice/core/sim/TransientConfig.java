package dev.bluespice.core.sim;

/**
 * Time-domain simulation configuration.
 *
 * @param stepSeconds simulation step size in seconds
 * @param stopSeconds final simulation time in seconds
 * @param startSeconds initial simulation time in seconds
 * @param saveInitialDc whether the backend should solve and save a DC initial state before the run
 */
public record TransientConfig(
        double stepSeconds,
        double stopSeconds,
        double startSeconds,
        boolean saveInitialDc
) {
    public TransientConfig {
        requireFinitePositive(stepSeconds, "stepSeconds");
        requireFinite(stopSeconds, "stopSeconds");
        requireFinite(startSeconds, "startSeconds");
        if (stopSeconds <= startSeconds) {
            throw new IllegalArgumentException("stopSeconds must be greater than startSeconds");
        }
    }

    /**
     * Creates a transient configuration sized for one game or simulation tick.
     */
    public static TransientConfig oneTick(double tickSeconds) {
        requireFinitePositive(tickSeconds, "tickSeconds");
        return new TransientConfig(tickSeconds / 100, tickSeconds, 0, true);
    }

    private static void requireFinitePositive(double value, String name) {
        requireFinite(value, name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
