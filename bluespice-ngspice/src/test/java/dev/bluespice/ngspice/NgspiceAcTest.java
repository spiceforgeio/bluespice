package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.testcommon.SimulationAssertions.tolerancePct;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.sim.AcConfig;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.testcommon.Circuits;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceAcTest {
    @Test
    void resistiveDividerMagnitudeAndPhaseMatchAnalyticalRatio() {
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(Circuits.acVoltageDivider(10.0, 0.0))) {
            var result = session.runAc(new AcConfig(50.0));

            assertTrue(result.converged());
            assertEquals(50.0, result.frequencyHz());
            assertNear(5.0, result.voltageMagnitude("vmid"), tolerancePct(0.1));
            assertNear(0.0, result.voltage("vmid").phaseDegrees(), 1e-6);
        }
    }

    @Test
    void rcLowPassMagnitudeAndPhaseMatchAnalyticalTransferFunction() {
        double frequency = 159.15494309189535;
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(Circuits.acRcLowPass(1.0, 0.0))) {
            var result = session.runAc(new AcConfig(frequency));

            double omegaRc = 2.0 * Math.PI * frequency * 1000.0 * 1.0E-6;
            double expectedMagnitude = 1.0 / Math.sqrt(1.0 + omegaRc * omegaRc);
            double expectedPhase = -Math.toDegrees(Math.atan(omegaRc));
            assertNear(expectedMagnitude, result.voltageMagnitude("vout"), tolerancePct(0.1));
            assertNear(expectedPhase, result.voltage("vout").phaseDegrees(), 0.1);
        }
    }

    @Test
    void sourcePhaseIsPreservedAtQuadrants() {
        double[] phases = {0.0, 90.0, -90.0, 180.0};
        try (NgspiceEngine engine = engine()) {
            for (double phase : phases) {
                try (var session = engine.openSession(Circuits.acVoltageDivider(10.0, phase))) {
                    var result = session.runAc(new AcConfig(60.0));

                    assertNear(5.0, result.voltageMagnitude("vmid"), tolerancePct(0.1));
                    assertNear(normalizeDegrees(phase), normalizeDegrees(result.voltage("vmid").phaseDegrees()), 0.1);
                }
            }
        }
    }

    @Test
    void exposesResistorAndVoltageSourceBranchCurrents() {
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(Circuits.acVoltageDivider(10.0, 0.0))) {
            var result = session.runAc(new AcConfig(50.0));

            assertNear(0.005, result.currentMagnitude("R1"), tolerancePct(0.1));
            assertNear(0.005, result.currentMagnitude("R2"), tolerancePct(0.1));
            assertNear(0.005, result.currentMagnitude("V1"), tolerancePct(0.1));
            assertNear(0.005, result.current("R1").real(), tolerancePct(0.1));
            assertNear(0.005, result.current("R2").real(), tolerancePct(0.1));
            assertNear(-0.005, result.current("V1").real(), tolerancePct(0.1));
            assertNear(0.0, result.current("R1").phaseDegrees(), 0.1);
            assertNear(0.0, result.current("R2").phaseDegrees(), 0.1);
            assertNear(180.0, normalizeDegrees(result.current("V1").phaseDegrees()), 0.1);
        }
    }

    @Test
    void runAcReflectsParameterAndTopologyChanges() {
        var circuit = Circuits.acVoltageDivider(10.0, 0.0);
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            assertNear(5.0, session.runAc(new AcConfig(50.0)).voltageMagnitude("vmid"), tolerancePct(0.1));

            circuit.updateValue("R2", new ComponentValue.Resistance(3000.0));
            session.onParameterChanged("R2", new ComponentValue.Resistance(3000.0));
            assertNear(7.5, session.runAc(new AcConfig(50.0)).voltageMagnitude("vmid"), tolerancePct(0.1));

            circuit.addComponent(RESISTOR, "R3",
                    new ComponentValue.Resistance(3000.0), circuit.getNode("vmid"), circuit.ground());
            session.onTopologyChanged();
            assertNear(6.0, session.runAc(new AcConfig(50.0)).voltageMagnitude("vmid"), tolerancePct(0.1));
        }
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

    private static void assertNear(double expected, double actual, double tolerance) {
        assertEquals(expected, actual, Math.abs(expected) * tolerance + 1e-12);
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        if (normalized <= -180.0) {
            normalized += 360.0;
        } else if (normalized > 180.0) {
            normalized -= 360.0;
        }
        return normalized;
    }
}
