package dev.bluespice.core.sim;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable DC operating-point result.
 *
 * @param nodeVoltages node label to voltage in volts
 * @param branchCurrents component id to branch current in amperes
 * @param converged whether the backend considers the result converged
 * @param solveTime backend solve duration
 */
public record OperatingPointResult(
        Map<String, Double> nodeVoltages,
        Map<String, Double> branchCurrents,
        boolean converged,
        Duration solveTime
) {
    public OperatingPointResult {
        nodeVoltages = Map.copyOf(Objects.requireNonNull(nodeVoltages, "nodeVoltages"));
        branchCurrents = Map.copyOf(Objects.requireNonNull(branchCurrents, "branchCurrents"));
        Objects.requireNonNull(solveTime, "solveTime");
    }
}
