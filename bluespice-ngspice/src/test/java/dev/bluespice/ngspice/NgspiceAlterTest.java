package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;
import static dev.bluespice.testcommon.SimulationAssertions.assertVoltageNear;
import static dev.bluespice.testcommon.SimulationAssertions.tolerancePct;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceAlterTest {
    @Test
    void alterResistor_dcResult_matchesFreshSession() {
        Circuit circuit = voltageDivider(1000.0, 1000.0, 10.0);
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            assertVoltageNear(session.runOperatingPoint(), "vmid", 5.0, tolerancePct(0.01));

            ComponentValue.Resistance updated = new ComponentValue.Resistance(3000.0);
            circuit.updateValue("R1", updated);
            session.onParameterChanged("R1", updated);
            OperatingPointResult altered = session.runOperatingPoint();

            try (NgspiceEngine freshEngine = engine();
                    var fresh = freshEngine.openSession(circuit.snapshot())) {
                assertEquals(
                        fresh.runOperatingPoint().nodeVoltages().get("vmid"),
                        altered.nodeVoltages().get("vmid"),
                        Math.abs(altered.nodeVoltages().get("vmid")) * tolerancePct(0.01));
            }
        }
    }

    @Test
    void alterVoltageSource_dcResult_matchesAnalytical() {
        Circuit circuit = voltageDivider(1000.0, 1000.0, 10.0);
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            ComponentValue.DCVoltage updated = new ComponentValue.DCVoltage(6.0);
            circuit.updateValue("V1", updated);
            session.onParameterChanged("V1", updated);

            assertVoltageNear(session.runOperatingPoint(), "vmid", 3.0, tolerancePct(0.01));
        }
    }

    @Test
    void alterCapacitor_dcSteadyState_unchanged() {
        Circuit circuit = dividerWithCapacitor();
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            double before = session.runOperatingPoint().nodeVoltages().get("vmid");

            ComponentValue.Capacitance updated = new ComponentValue.Capacitance(10.0E-6);
            circuit.updateValue("C1", updated);
            session.onParameterChanged("C1", updated);

            assertEquals(
                    before,
                    session.runOperatingPoint().nodeVoltages().get("vmid"),
                    Math.abs(before) * tolerancePct(0.01));
        }
    }

    @Test
    void batchedAlters_appliedBeforeNextOp() {
        Circuit circuit = voltageDivider(1000.0, 1000.0, 10.0);
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            ComponentValue.Resistance r1 = new ComponentValue.Resistance(3000.0);
            ComponentValue.Resistance r2 = new ComponentValue.Resistance(2000.0);
            circuit.updateValue("R1", r1);
            circuit.updateValue("R2", r2);
            session.onParameterChanged("R1", r1);
            session.onParameterChanged("R2", r2);

            assertVoltageNear(session.runOperatingPoint(), "vmid", 4.0, tolerancePct(0.01));
        }
    }

    @Test
    void topologyChangeAfterAlter_clearsAlterQueue() {
        Circuit circuit = voltageDivider(1000.0, 1000.0, 10.0);
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            session.onParameterChanged("R1", new ComponentValue.Resistance(3000.0));

            Node vmid = circuit.getNode("vmid");
            circuit.addComponent(RESISTOR, "R3", new ComponentValue.Resistance(1000.0), vmid, circuit.ground());
            session.onTopologyChanged();

            assertVoltageNear(session.runOperatingPoint(), "vmid", 10.0 / 3.0, tolerancePct(0.01));
        }
    }

    private static Circuit voltageDivider(double r1, double r2, double sourceVolts) {
        Circuit circuit = Circuit.empty("phase4-voltage-divider");
        Node vin = circuit.addNode("vin");
        Node vmid = circuit.addNode("vmid");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(sourceVolts), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(r1), vin, vmid);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(r2), vmid, circuit.ground());
        return circuit;
    }

    private static Circuit dividerWithCapacitor() {
        Circuit circuit = voltageDivider(1000.0, 1000.0, 10.0);
        circuit.addComponent(CAPACITOR, "C1", new ComponentValue.Capacitance(1.0E-6),
                circuit.getNode("vmid"), circuit.ground());
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
