package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OperatingPointResultTest {
    @Test
    void defensivelyCopiesMaps() {
        Map<String, Double> nodes = new LinkedHashMap<>();
        nodes.put("vout", 2.5);
        Map<String, Double> branches = new LinkedHashMap<>();
        branches.put("V1", 0.01);

        OperatingPointResult result = new OperatingPointResult(nodes, branches, true, Duration.ofMillis(1));
        nodes.put("vout", 99.0);
        branches.put("V1", 99.0);

        assertEquals(2.5, result.nodeVoltages().get("vout"));
        assertEquals(0.01, result.branchCurrents().get("V1"));
        assertThrows(UnsupportedOperationException.class, () -> result.nodeVoltages().put("x", 1.0));
    }
}
