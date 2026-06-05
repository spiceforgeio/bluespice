package dev.bluespice.ngspice.result;

import com.sun.jna.Pointer;
import dev.bluespice.core.sim.AcResult;
import dev.bluespice.core.sim.Complex;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.ngspice.NgspiceLibrary;
import dev.bluespice.ngspice.NgspiceVectorInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Converts ngspice vectors from the current plot into BlueSpice result objects.
 */
public final class VectorExtractor {
    private VectorExtractor() {}

    /**
     * Reads a scalar vector value or throws when the vector is missing.
     */
    public static double readScalar(String vectorName) {
        return findVector(vectorName)
                .orElseThrow(() -> new IllegalStateException("missing ngspice vector: " + vectorName));
    }

    /**
     * Reads all real values for a vector or throws when the vector is missing.
     */
    public static double[] readArray(String vectorName) {
        Pointer pointer = findVectorPointer(vectorName);
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            throw new IllegalStateException("missing ngspice vector: " + vectorName);
        }
        return NgspiceVectorInfo.realValues(pointer);
    }

    /**
     * Reads all complex values for a vector or throws when the vector is missing.
     */
    public static Complex[] readComplexArray(String vectorName) {
        Pointer pointer = findVectorPointer(vectorName);
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            throw new IllegalStateException("missing ngspice vector: " + vectorName);
        }
        return NgspiceVectorInfo.complexValues(pointer);
    }

    /**
     * Extracts DC operating-point vectors for selected nodes and branch components.
     */
    public static OperatingPointResult extractDcOp(
            List<String> nodeNames,
            List<String> branchComponentIds,
            Duration solveTime) {
        Objects.requireNonNull(nodeNames, "nodeNames");
        Objects.requireNonNull(branchComponentIds, "branchComponentIds");
        Objects.requireNonNull(solveTime, "solveTime");

        Map<String, Double> nodeVoltages = new LinkedHashMap<>();
        boolean complete = true;
        for (String nodeName : nodeNames) {
            OptionalDouble value = findVector("v(" + nodeName + ")");
            if (value.isPresent()) {
                nodeVoltages.put(nodeName, value.getAsDouble());
            } else {
                complete = false;
            }
        }

        Map<String, Double> branchCurrents = new LinkedHashMap<>();
        for (String componentId : branchComponentIds) {
            OptionalDouble value = findVector(componentId + "#branch");
            if (value.isPresent()) {
                branchCurrents.put(componentId, value.getAsDouble());
            } else {
                complete = false;
            }
        }

        return new OperatingPointResult(nodeVoltages, branchCurrents, complete, solveTime);
    }

    /**
     * Extracts transient vectors for selected nodes and branch components.
     */
    public static TransientResult extractTransient(
            List<String> nodeNames,
            List<String> branchComponentIds,
            boolean completed,
            Duration solveTime) {
        Objects.requireNonNull(nodeNames, "nodeNames");
        Objects.requireNonNull(branchComponentIds, "branchComponentIds");
        Objects.requireNonNull(solveTime, "solveTime");

        double[] timePoints = findVectorArray("time").orElse(new double[] {0.0});
        Map<String, double[]> nodeVoltages = new LinkedHashMap<>();
        for (String nodeName : nodeNames) {
            findVectorArray("v(" + nodeName + ")")
                    .ifPresent(values -> nodeVoltages.put(nodeName, fitLength(values, timePoints.length)));
        }

        Map<String, double[]> branchCurrents = new LinkedHashMap<>();
        for (String componentId : branchComponentIds) {
            findVectorArray(componentId + "#branch")
                    .ifPresent(values -> branchCurrents.put(componentId, fitLength(values, timePoints.length)));
        }

        return new TransientResult(timePoints, nodeVoltages, branchCurrents, completed, solveTime);
    }

    /**
     * Extracts fixed-frequency AC vectors for selected nodes and branch components.
     */
    public static AcResult extractAc(
            List<String> nodeNames,
            List<String> branchComponentIds,
            double frequencyHz,
            Duration solveTime) {
        Objects.requireNonNull(nodeNames, "nodeNames");
        Objects.requireNonNull(branchComponentIds, "branchComponentIds");
        Objects.requireNonNull(solveTime, "solveTime");

        Map<String, Complex> nodeVoltages = new LinkedHashMap<>();
        boolean complete = true;
        for (String nodeName : nodeNames) {
            java.util.Optional<Complex> value = findComplexVector("v(" + nodeName + ")");
            if (value.isPresent()) {
                nodeVoltages.put(nodeName, value.get());
            } else {
                complete = false;
            }
        }

        Map<String, Complex> branchCurrents = new LinkedHashMap<>();
        for (String componentId : branchComponentIds) {
            java.util.Optional<Complex> value = findComplexVector(componentId + "#branch");
            if (value.isPresent()) {
                branchCurrents.put(componentId, value.get());
            } else {
                complete = false;
            }
        }

        return new AcResult(frequencyHz, nodeVoltages, branchCurrents, complete, solveTime);
    }

    /**
     * Finds the last real value of a vector, if present.
     */
    public static OptionalDouble findLastValue(String vectorName) {
        java.util.Optional<double[]> values = findVectorArray(vectorName);
        if (values.isEmpty()) {
            return OptionalDouble.empty();
        }
        double[] array = values.get();
        return OptionalDouble.of(array[array.length - 1]);
    }

    private static OptionalDouble findVector(String vectorName) {
        Pointer pointer = findVectorPointer(vectorName);
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(NgspiceVectorInfo.firstRealValue(pointer));
    }

    private static java.util.Optional<double[]> findVectorArray(String vectorName) {
        Pointer pointer = findVectorPointer(vectorName);
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(NgspiceVectorInfo.realValues(pointer));
        } catch (IllegalStateException e) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Complex> findComplexVector(String vectorName) {
        Pointer pointer = findVectorPointer(vectorName);
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            return java.util.Optional.empty();
        }
        try {
            Complex[] values = NgspiceVectorInfo.complexValues(pointer);
            if (values.length == 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(values[0]);
        } catch (IllegalStateException e) {
            try {
                return java.util.Optional.of(new Complex(NgspiceVectorInfo.firstRealValue(pointer), 0.0));
            } catch (IllegalStateException ignored) {
                return java.util.Optional.empty();
            }
        }
    }

    private static double[] fitLength(double[] values, int length) {
        if (values.length == length) {
            return values;
        }
        if (values.length == 0) {
            throw new IllegalStateException("ngspice vector has no real data");
        }
        double[] copy = Arrays.copyOf(values, length);
        if (values.length < length) {
            Arrays.fill(copy, values.length, length, values[values.length - 1]);
        }
        return copy;
    }

    private static Pointer findVectorPointer(String vectorName) {
        Objects.requireNonNull(vectorName, "vectorName");
        String plot = NgspiceLibrary.ngSpice_CurPlot();
        String lower = vectorName.toLowerCase(Locale.ROOT);
        String[] candidates = plot == null || plot.isBlank()
                ? new String[] {vectorName, lower}
                : new String[] {vectorName, lower, plot + "." + vectorName, plot + "." + lower};
        for (String candidate : candidates) {
            Pointer pointer = NgspiceLibrary.ngGet_Vec_Info(candidate);
            if (pointer != null && Pointer.nativeValue(pointer) != 0L) {
                return pointer;
            }
        }
        return null;
    }
}
