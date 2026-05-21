package dev.bluespice.core.sim;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

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
