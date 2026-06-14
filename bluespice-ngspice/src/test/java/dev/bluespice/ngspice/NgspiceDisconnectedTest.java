package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.INDUCTOR;
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
import dev.bluespice.core.sim.AcConfig;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceDisconnectedTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void disconnectedOperatingPointWithSingleWorker_doesNotHang() {
        Circuit circuit = disconnectedDividers();
        try (NgspiceEngine engine = engine(1);
                SimulationSession session = engine.openSession(circuit)) {
            assertInstanceOf(SplitSession.class, session);

            OperatingPointResult result = session.runOperatingPoint();

            assertVoltageNear(result, "vout1", 3.0, tolerancePct(0.01));
            assertVoltageNear(result, "vout2", 6.0, tolerancePct(0.01));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void disconnectedAcWithSingleWorker_doesNotHang() {
        Circuit circuit = disconnectedAcDividers();
        try (NgspiceEngine engine = engine(1);
                SimulationSession session = engine.openSession(circuit)) {
            assertInstanceOf(SplitSession.class, session);

            var result = session.runAc(new AcConfig(50.0));

            assertEquals(3.0, result.voltageMagnitude("vout1"), 0.003);
            assertEquals(6.0, result.voltageMagnitude("vout2"), 0.006);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void coupledIsolatedAcWindingsStayInSameSplitSubSession() {
        Circuit circuit = coupledWindingsAndIndependentDivider();
        try (NgspiceEngine engine = engine(1);
                SimulationSession session = engine.openSession(circuit)) {
            assertInstanceOf(SplitSession.class, session);
            assertEquals(2, Topology.split(circuit).size());

            var result = session.runAc(new AcConfig(50.0));

            var secondaryVoltage = result.voltage("secondaryHigh").minus(result.voltage("secondaryLow"));
            assertEquals(20.0, secondaryVoltage.magnitude(), 0.1);
            assertEquals(3.0, result.voltageMagnitude("vout"), 0.003);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void singleWorkerReleasedAfterSequentialSplitSessionClose() {
        try (NgspiceEngine engine = engine(1)) {
            try (SimulationSession session = engine.openSession(disconnectedDividers())) {
                OperatingPointResult result = session.runOperatingPoint();
                assertVoltageNear(result, "vout1", 3.0, tolerancePct(0.01));
                assertVoltageNear(result, "vout2", 6.0, tolerancePct(0.01));
            }

            try (NgspiceSession session = engine.openSingleSession(divider("after-split-close", 10.0, 1000.0, 1000.0))) {
                assertEquals(5.0, session.runOperatingPoint().nodeVoltages().get("vout"), 0.005);
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void onParameterChangedWithSingleWorker_onlyAffectsCorrectSubcircuit() {
        Circuit circuit = disconnectedDividers();
        try (NgspiceEngine engine = engine(1);
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

    private static Circuit disconnectedAcDividers() {
        Circuit circuit = Circuit.empty("disconnected-ac-dividers");
        Node vin1 = circuit.addNode("vin1");
        Node vout1 = circuit.addNode("vout1");
        Node vin2 = circuit.addNode("vin2");
        Node vout2 = circuit.addNode("vout2");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.ACVoltage(6.0, 0.0), vin1, circuit.ground());
        circuit.addComponent(RESISTOR, "R1A", new ComponentValue.Resistance(1000.0), vin1, vout1);
        circuit.addComponent(RESISTOR, "R1B", new ComponentValue.Resistance(1000.0), vout1, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "V2", new ComponentValue.ACVoltage(8.0, 0.0), vin2, circuit.ground());
        circuit.addComponent(RESISTOR, "R2A", new ComponentValue.Resistance(1000.0), vin2, vout2);
        circuit.addComponent(RESISTOR, "R2B", new ComponentValue.Resistance(3000.0), vout2, circuit.ground());
        return circuit;
    }

    private static Circuit coupledWindingsAndIndependentDivider() {
        Circuit circuit = Circuit.empty("coupled-and-independent");
        Node source = circuit.addNode("source");
        Node primary = circuit.addNode("primary");
        Node secondaryHigh = circuit.addNode("secondaryHigh");
        Node secondaryLow = circuit.addNode("secondaryLow");
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1",
                new ComponentValue.ACVoltage(10.0, 0.0), source, circuit.ground());
        circuit.addComponent(RESISTOR, "Rdrive", new ComponentValue.Resistance(1.0E-3), source, primary);
        circuit.addComponent(INDUCTOR, "Lp", new ComponentValue.Inductance(1.0), primary, circuit.ground());
        circuit.addComponent(INDUCTOR, "Ls", new ComponentValue.Inductance(4.0), secondaryHigh, secondaryLow);
        circuit.addComponent(RESISTOR, "Rref",
                new ComponentValue.Resistance(1.0E12), secondaryLow, circuit.ground());
        circuit.addMutualCoupling("K1", "Lp", "Ls", 1.0);
        circuit.addComponent(VOLTAGE_SOURCE, "V2",
                new ComponentValue.ACVoltage(6.0, 0.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), vin, vout);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(1000.0), vout, circuit.ground());
        return circuit;
    }

    private static Circuit divider(String name, double sourceVoltage, double topResistance, double bottomResistance) {
        Circuit circuit = Circuit.empty(name);
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(sourceVoltage), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(topResistance), vin, vout);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(bottomResistance), vout, circuit.ground());
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
        return engine(4);
    }

    private NgspiceEngine engine(int maxWorkers) {
        return NgspiceEngine.load(new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                maxWorkers,
                EngineConfig.defaults().simulationTimeout(),
                false));
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
