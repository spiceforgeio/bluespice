package dev.bluespice.ngspice;

import static dev.bluespice.testcommon.SimulationAssertions.assertCurrentNear;
import static dev.bluespice.testcommon.SimulationAssertions.assertVoltageNear;
import static dev.bluespice.testcommon.SimulationAssertions.tolerancePct;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.testcommon.AnalyticalResults;
import dev.bluespice.testcommon.Circuits;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceDcOpTest {
    @Test
    void voltageDivider_vmid_matchesAnalytical() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(Circuits.voltageDivider())) {
            assertVoltageNear(
                    session.runOperatingPoint(),
                    "vmid",
                    AnalyticalResults.voltageDivider_vmid(),
                    tolerancePct(0.1));
        }
    }

    @Test
    void rcSteadyState_vcap_equalsSourceVoltage() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(Circuits.rcSmall())) {
            assertVoltageNear(
                    session.runOperatingPoint(),
                    "vout",
                    AnalyticalResults.rcSmall_vout_dcSteady(),
                    tolerancePct(0.1));
        }
    }

    @Test
    void currentDivider_branchCurrent_matchesAnalytical() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(Circuits.currentDivider())) {
            assertCurrentNear(
                    session.runOperatingPoint(),
                    "R1",
                    AnalyticalResults.currentDivider_iR1(),
                    tolerancePct(0.1));
        }
    }

    @Test
    void bjtAmplifier_converges() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(Circuits.bjtAmp())) {
            var result = session.runOperatingPoint();
            assertTrue(result.converged());
            assertTrue(result.nodeVoltages().get("vout") > 0.0);
        }
    }

    @Test
    void mosfetSwitch_converges() {
        try (NgspiceEngine engine = engine();
                NgspiceSession session = engine.openSession(Circuits.mosfetSwitch())) {
            assertTrue(session.runOperatingPoint().converged());
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
}
