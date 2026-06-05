package dev.bluespice.core.sim;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Immutable fixed-frequency AC analysis result.
 *
 * @param frequencyHz analysis frequency in hertz
 * @param nodeVoltages node label to RMS voltage phasor in volts
 * @param branchCurrents component id to RMS branch-current phasor in amperes
 * @param converged whether the backend considers the result converged
 * @param solveTime backend solve duration
 */
public record AcResult(
        double frequencyHz,
        Map<String, Complex> nodeVoltages,
        Map<String, Complex> branchCurrents,
        boolean converged,
        Duration solveTime
) {
    public AcResult {
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("frequencyHz must be positive");
        }
        nodeVoltages = Map.copyOf(Objects.requireNonNull(nodeVoltages, "nodeVoltages"));
        branchCurrents = Map.copyOf(Objects.requireNonNull(branchCurrents, "branchCurrents"));
        Objects.requireNonNull(solveTime, "solveTime");
    }

    /**
     * Returns the node voltage phasor.
     */
    public Complex voltage(String node) {
        return require(nodeVoltages, node, "node voltage");
    }

    /**
     * Returns the node voltage RMS magnitude.
     */
    public double voltageMagnitude(String node) {
        return voltage(node).magnitude();
    }

    /**
     * Returns the branch current phasor.
     */
    public Complex current(String componentId) {
        return require(branchCurrents, componentId, "branch current");
    }

    /**
     * Returns the branch current RMS magnitude.
     */
    public double currentMagnitude(String componentId) {
        return current(componentId).magnitude();
    }

    private static Complex require(Map<String, Complex> values, String key, String label) {
        Complex value = values.get(key);
        if (value == null) {
            throw new NoSuchElementException(label + " not found: " + key);
        }
        return value;
    }
}
