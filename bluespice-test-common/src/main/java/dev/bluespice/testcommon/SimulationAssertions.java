package dev.bluespice.testcommon;

import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.TransientResult;
import org.junit.jupiter.api.Assertions;

public final class SimulationAssertions {
    private SimulationAssertions() {}

    public static void assertVoltageNear(OperatingPointResult r, String node, double expected, double tolerancePct) {
        Double actual = r.nodeVoltages().get(node);
        Assertions.assertNotNull(actual, "missing node voltage: " + node);
        Assertions.assertEquals(expected, actual, Math.abs(expected) * tolerancePct);
    }

    public static void assertCurrentNear(OperatingPointResult r, String componentId, double expected, double tolerancePct) {
        Double actual = r.branchCurrents().get(componentId);
        Assertions.assertNotNull(actual, "missing branch current: " + componentId);
        Assertions.assertEquals(expected, actual, Math.abs(expected) * tolerancePct);
    }

    public static void assertVoltageAt(
            TransientResult r,
            String node,
            double time,
            double expected,
            double tolerancePct) {
        Assertions.assertEquals(expected, r.voltageAt(node, time), Math.abs(expected) * tolerancePct);
    }

    public static double tolerancePct(double pct) {
        return pct / 100.0;
    }
}
