package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AcResultTest {
    @Test
    void defensivelyCopiesMapsAndExposesMagnitudeHelpers() {
        Map<String, Complex> nodes = new LinkedHashMap<>();
        nodes.put("vout", new Complex(3.0, 4.0));
        Map<String, Complex> branches = new LinkedHashMap<>();
        branches.put("R1", new Complex(0.003, 0.004));

        AcResult result = new AcResult(50.0, nodes, branches, true, Duration.ofMillis(1));
        nodes.put("vout", new Complex(99.0, 0.0));
        branches.put("R1", new Complex(99.0, 0.0));

        assertEquals(new Complex(3.0, 4.0), result.voltage("vout"));
        assertEquals(5.0, result.voltageMagnitude("vout"), 1e-12);
        assertEquals(0.005, result.currentMagnitude("R1"), 1e-12);
        assertThrows(UnsupportedOperationException.class, () -> result.nodeVoltages().put("x", new Complex(1.0, 0.0)));
    }

    @Test
    void validatesAndReportsMissingKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                new AcResult(0.0, Map.of(), Map.of(), true, Duration.ZERO));
        AcResult result = new AcResult(60.0, Map.of(), Map.of(), true, Duration.ZERO);

        assertThrows(NoSuchElementException.class, () -> result.voltage("missing"));
        assertThrows(NoSuchElementException.class, () -> result.current("missing"));
    }
}
