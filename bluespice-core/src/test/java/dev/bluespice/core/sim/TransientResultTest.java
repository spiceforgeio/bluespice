package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TransientResultTest {
    @Test
    void interpolatesAndClamps() {
        TransientResult result = sampleTransientResult();

        assertEquals(0.0, result.voltageAt("vout", -1.0));
        assertEquals(1.0, result.voltageAt("vout", 0.5));
        assertEquals(2.0, result.voltageAt("vout", 1.0));
        assertEquals(4.0, result.voltageAt("vout", 10.0));
        assertEquals(4.0, result.voltageAtEnd("vout"));
        assertEquals(0.2, result.currentAtEnd("V1"));
    }

    @Test
    void defensivelyCopiesArraysAndMaps() {
        double[] time = {0.0, 1.0};
        double[] volts = {0.0, 1.0};
        Map<String, double[]> nodeVoltages = new LinkedHashMap<>();
        nodeVoltages.put("vout", volts);

        TransientResult result = new TransientResult(
                time, nodeVoltages, Map.of("V1", new double[] {0.0, 0.1}), true, Duration.ZERO);
        time[1] = 99.0;
        volts[1] = 99.0;
        result.timePoints()[1] = 123.0;
        result.nodeVoltages().get("vout")[1] = 123.0;

        assertArrayEquals(new double[] {0.0, 1.0}, result.timePoints());
        assertArrayEquals(new double[] {0.0, 1.0}, result.nodeVoltages().get("vout"));
        assertArrayEquals(new double[] {0.0, 0.1}, result.branchCurrents().get("V1"));
    }

    @Test
    void validatesSeriesShapeAndTimeOrdering() {
        assertThrows(IllegalArgumentException.class, () -> new TransientResult(
                new double[0], Map.of(), Map.of(), true, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TransientResult(
                new double[] {0.0, 0.0}, Map.of(), Map.of(), true, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TransientResult(
                new double[] {1.0, 0.0}, Map.of(), Map.of(), true, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TransientResult(
                new double[] {0.0, 1.0},
                Map.of("vout", new double[] {0.0}),
                Map.of(),
                true,
                Duration.ZERO));
        assertThrows(NoSuchElementException.class, () -> sampleTransientResult().voltageAtEnd("missing"));
    }

    private TransientResult sampleTransientResult() {
        return new TransientResult(
                new double[] {0.0, 1.0, 2.0},
                Map.of("vout", new double[] {0.0, 2.0, 4.0}),
                Map.of("V1", new double[] {0.0, 0.1, 0.2}),
                true,
                Duration.ofMillis(1));
    }
}
