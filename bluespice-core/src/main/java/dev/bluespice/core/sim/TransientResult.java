package dev.bluespice.core.sim;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Immutable time-domain simulation result.
 *
 * @param timePoints strictly ascending sample times in seconds
 * @param nodeVoltages node label to voltage series in volts
 * @param branchCurrents component id to branch current series in amperes
 * @param completed whether the transient reached its configured stop time
 * @param solveTime backend solve duration
 */
public record TransientResult(
        double[] timePoints,
        Map<String, double[]> nodeVoltages,
        Map<String, double[]> branchCurrents,
        boolean completed,
        Duration solveTime
) {
    public TransientResult {
        Objects.requireNonNull(timePoints, "timePoints");
        if (timePoints.length == 0) {
            throw new IllegalArgumentException("timePoints must not be empty");
        }
        timePoints = timePoints.clone();
        for (int i = 1; i < timePoints.length; i++) {
            if (timePoints[i] <= timePoints[i - 1]) {
                throw new IllegalArgumentException("timePoints must be strictly ascending");
            }
        }
        nodeVoltages = copySeriesMap(nodeVoltages, timePoints.length, "nodeVoltages");
        branchCurrents = copySeriesMap(branchCurrents, timePoints.length, "branchCurrents");
        Objects.requireNonNull(solveTime, "solveTime");
    }

    @Override
    public double[] timePoints() {
        return timePoints.clone();
    }

    @Override
    public Map<String, double[]> nodeVoltages() {
        return copySeriesMap(nodeVoltages, timePoints.length, "nodeVoltages");
    }

    @Override
    public Map<String, double[]> branchCurrents() {
        return copySeriesMap(branchCurrents, timePoints.length, "branchCurrents");
    }

    /**
     * Returns the linearly interpolated voltage at a sample time.
     */
    public double voltageAt(String node, double time) {
        return valueAt(nodeVoltages, node, time);
    }

    /**
     * Returns the final voltage for a node.
     */
    public double voltageAtEnd(String node) {
        return valueAtEnd(nodeVoltages, node);
    }

    /**
     * Returns the final branch current for a component.
     */
    public double currentAtEnd(String componentId) {
        return valueAtEnd(branchCurrents, componentId);
    }

    private double valueAt(Map<String, double[]> seriesMap, String key, double time) {
        double[] values = requireSeries(seriesMap, key);
        if (time <= timePoints[0]) {
            return values[0];
        }
        int last = timePoints.length - 1;
        if (time >= timePoints[last]) {
            return values[last];
        }
        int insertionPoint = Arrays.binarySearch(timePoints, time);
        if (insertionPoint >= 0) {
            return values[insertionPoint];
        }
        int upper = -insertionPoint - 1;
        int lower = upper - 1;
        double span = timePoints[upper] - timePoints[lower];
        double fraction = (time - timePoints[lower]) / span;
        return values[lower] + (values[upper] - values[lower]) * fraction;
    }

    private double valueAtEnd(Map<String, double[]> seriesMap, String key) {
        double[] values = requireSeries(seriesMap, key);
        return values[values.length - 1];
    }

    private static double[] requireSeries(Map<String, double[]> seriesMap, String key) {
        double[] values = seriesMap.get(key);
        if (values == null) {
            throw new NoSuchElementException("series not found: " + key);
        }
        return values;
    }

    private static Map<String, double[]> copySeriesMap(Map<String, double[]> source, int expectedLength, String name) {
        Objects.requireNonNull(source, name);
        Map<String, double[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : source.entrySet()) {
            double[] values = Objects.requireNonNull(entry.getValue(), "series values").clone();
            if (values.length != expectedLength) {
                throw new IllegalArgumentException(entry.getKey() + " length does not match timePoints");
            }
            copy.put(entry.getKey(), values);
        }
        return Map.copyOf(copy);
    }
}
