package dev.bluespice.core.sim;

/**
 * Fixed-frequency AC analysis configuration.
 *
 * @param frequencyHz analysis frequency in hertz
 */
public record AcConfig(double frequencyHz) {
    public AcConfig {
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("frequencyHz must be positive");
        }
    }
}
