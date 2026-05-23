package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.INDUCTOR;
import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;
import static dev.bluespice.testcommon.SimulationAssertions.assertVoltageAt;
import static dev.bluespice.testcommon.SimulationAssertions.tolerancePct;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceTransientTest {
    @Test
    void rcCharge_voltageFollowsExponential() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(rcCharge(1.0E-6))) {
            TransientResult result = session.runTransient(new TransientConfig(1.0E-5, 0.005, 0.0, false));

            assertVoltageAt(result, "vout", 0.001, 5.0 * (1.0 - Math.exp(-1.0)), tolerancePct(1.0));
            assertVoltageAt(result, "vout", 0.002, 5.0 * (1.0 - Math.exp(-2.0)), tolerancePct(1.0));
            assertVoltageAt(result, "vout", 0.005, 5.0 * (1.0 - Math.exp(-5.0)), tolerancePct(1.0));
        }
    }

    @Test
    void rlcStepResponse_peakOvershoot_withinTolerance() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(rlcUnderdamped())) {
            TransientResult result = session.runTransient(new TransientConfig(1.0E-6, 0.003, 0.0, false));
            double peak = Arrays.stream(result.nodeVoltages().get("vout")).max().orElseThrow();
            double overshoot = peak / 5.0 - 1.0;

            assertTrue(overshoot > 0.50, "overshoot too low: " + overshoot);
            assertTrue(overshoot < 0.70, "overshoot too high: " + overshoot);
        }
    }

    @Test
    void cancelTransient_icContinuity_voltageMatchesCaptured() throws Exception {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(rcCharge(1.0E-3))) {
            CompletableFuture<TransientResult> future = CompletableFuture.supplyAsync(
                    () -> session.runTransient(new TransientConfig(1.0E-5, 5.0, 0.0, false)));
            waitUntilRunning(session);
            Thread.sleep(5);

            session.cancelTransient();

            TransientResult cancelled = future.get(5, TimeUnit.SECONDS);
            assertFalse(cancelled.completed());
            double captured = cancelled.voltageAtEnd("vout");

            TransientResult restarted = session.runTransient(new TransientConfig(1.0E-4, 0.01, 0.0, true));
            double restartInitial = restarted.voltageAt("vout", restarted.timePoints()[0]);
            org.junit.jupiter.api.Assertions.assertEquals(captured, restartInitial, Math.abs(captured) * tolerancePct(0.1));
        }
    }

    @Test
    void gameLoop_100ticks_monotonicChargeRc() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(rcCharge(1.0E-6))) {
            double previous = 0.0;
            for (int i = 0; i < 100; i++) {
                TransientResult result = session.runTransient(new TransientConfig(1.0E-6, 1.0E-4, 0.0, false));
                double voltage = result.voltageAtEnd("vout");
                assertTrue(voltage + 1.0E-9 >= previous, "tick " + i + " voltage regressed");
                previous = voltage;
            }
            assertTrue(previous > 4.9);
        }
    }

    private void waitUntilRunning(NgspiceSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!session.isTransientRunning() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(session.isTransientRunning(), "transient did not start");
    }

    private static Circuit rcCharge(double capacitance) {
        Circuit circuit = Circuit.empty("rc-charge");
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), vin, vout);
        circuit.addComponent(CAPACITOR, "C1", new ComponentValue.Capacitance(capacitance), vout, circuit.ground());
        return circuit;
    }

    private static Circuit rlcUnderdamped() {
        Circuit circuit = Circuit.empty("rlc-underdamped");
        Node vin = circuit.addNode("vin");
        Node n1 = circuit.addNode("n1");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(10.0), vin, n1);
        circuit.addComponent(INDUCTOR, "L1", new ComponentValue.Inductance(1.0E-3), n1, vout);
        circuit.addComponent(CAPACITOR, "C1", new ComponentValue.Capacitance(1.0E-6), vout, circuit.ground());
        return circuit;
    }

    private NgspiceEngine engine() {
        return NgspiceEngine.load(new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false));
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
