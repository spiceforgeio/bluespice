package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;
import static dev.bluespice.testcommon.SimulationAssertions.assertVoltageNear;
import static dev.bluespice.testcommon.SimulationAssertions.tolerancePct;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.circuit.Topology;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceDisconnectedTest {
    @Test
    void twoDisconnectedRcCircuits_simulatedIndependently() {
        Circuit circuit = disconnectedRcFilters();
        try (NgspiceEngine engine = engine();
                SimulationSession session = engine.openSession(circuit)) {
            assertInstanceOf(SplitSession.class, session);

            OperatingPointResult result = session.runOperatingPoint();

            assertVoltageNear(result, "vout1", 5.0, tolerancePct(0.01));
            assertVoltageNear(result, "vout2", 3.0, tolerancePct(0.01));
        }
    }

    @Test
    void splitSession_resultsMatchSeparateSingleSessions() {
        Circuit circuit = disconnectedDividers();
        try (NgspiceEngine engine = engine();
                SimulationSession split = engine.openSession(circuit)) {
            OperatingPointResult splitResult = split.runOperatingPoint();
            Map<String, Double> expectedVoltages = new LinkedHashMap<>();
            Map<String, Double> expectedCurrents = new LinkedHashMap<>();

            for (Circuit part : Topology.split(circuit)) {
                try (NgspiceSession single = engine.openSingleSession(part)) {
                    OperatingPointResult result = single.runOperatingPoint();
                    expectedVoltages.putAll(result.nodeVoltages());
                    expectedCurrents.putAll(result.branchCurrents());
                }
            }

            assertMapNear(expectedVoltages, splitResult.nodeVoltages(), tolerancePct(0.01));
            assertMapNear(expectedCurrents, splitResult.branchCurrents(), tolerancePct(0.01));
        }
    }

    @Test
    void onParameterChanged_onlyAffectsCorrectSubSession() {
        Circuit circuit = disconnectedDividers();
        try (NgspiceEngine engine = engine();
                SimulationSession session = engine.openSession(circuit)) {
            OperatingPointResult before = session.runOperatingPoint();

            ComponentValue.Resistance updated = new ComponentValue.Resistance(3000.0);
            circuit.updateValue("R1A", updated);
            session.onParameterChanged("R1A", updated);
            OperatingPointResult after = session.runOperatingPoint();

            assertEquals(3.0, before.nodeVoltages().get("vout1"), 0.003);
            assertEquals(1.5, after.nodeVoltages().get("vout1"), 0.0015);
            assertEquals(0.0015, after.branchCurrents().get("R1A"), 1.5E-6);
            assertEquals(
                    before.nodeVoltages().get("vout2"),
                    after.nodeVoltages().get("vout2"),
                    Math.abs(before.nodeVoltages().get("vout2")) * tolerancePct(0.01));
        }
    }

    private static Circuit disconnectedRcFilters() {
        Circuit circuit = Circuit.empty("disconnected-rc");
        Node vin1 = circuit.addNode("vin1");
        Node vout1 = circuit.addNode("vout1");
        Node vin2 = circuit.addNode("vin2");
        Node vout2 = circuit.addNode("vout2");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin1, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), vin1, vout1);
        circuit.addComponent(CAPACITOR, "C1", new ComponentValue.Capacitance(1.0E-6), vout1, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "V2", new ComponentValue.DCVoltage(3.0), vin2, circuit.ground());
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(1000.0), vin2, vout2);
        circuit.addComponent(CAPACITOR, "C2", new ComponentValue.Capacitance(1.0E-6), vout2, circuit.ground());
        return circuit;
    }

    private static Circuit disconnectedDividers() {
        Circuit circuit = Circuit.empty("disconnected-dividers");
        Node vin1 = circuit.addNode("vin1");
        Node vout1 = circuit.addNode("vout1");
        Node vin2 = circuit.addNode("vin2");
        Node vout2 = circuit.addNode("vout2");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(6.0), vin1, circuit.ground());
        circuit.addComponent(RESISTOR, "R1A", new ComponentValue.Resistance(1000.0), vin1, vout1);
        circuit.addComponent(RESISTOR, "R1B", new ComponentValue.Resistance(1000.0), vout1, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "V2", new ComponentValue.DCVoltage(8.0), vin2, circuit.ground());
        circuit.addComponent(RESISTOR, "R2A", new ComponentValue.Resistance(1000.0), vin2, vout2);
        circuit.addComponent(RESISTOR, "R2B", new ComponentValue.Resistance(3000.0), vout2, circuit.ground());
        return circuit;
    }

    private static void assertMapNear(Map<String, Double> expected, Map<String, Double> actual, double tolerance) {
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, Double> entry : expected.entrySet()) {
            double expectedValue = entry.getValue();
            assertEquals(expectedValue, actual.get(entry.getKey()), Math.max(Math.abs(expectedValue), 1.0) * tolerance);
        }
    }

    private NgspiceEngine engine() {
        return NgspiceEngine.load(new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                4,
                EngineConfig.defaults().simulationTimeout(),
                false));
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
