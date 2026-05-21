package dev.bluespice.ngspice.stub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentType;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.testcommon.AnalyticalResults;
import dev.bluespice.testcommon.Circuits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class StubEngineTest {
    @Test
    void coversSimulationSessionContract() {
        var circuit = Circuits.rcFilter();
        try (var engine = new StubEngine(); var session = engine.openSession(circuit)) {
            assertEquals("stub", engine.backendName());
            assertEquals("phase-1", engine.backendVersion());
            assertSame(circuit, session.circuit());
            assertFalse(session.isTransientRunning());

            OperatingPointResult op = session.runOperatingPoint();
            assertTrue(op.converged());
            assertEquals(AnalyticalResults.STUB_RC_FILTER_VOUT_DC, op.nodeVoltages().get("vout"));
            assertEquals(0.0, op.branchCurrents().get("R1"));

            TransientResult tran = session.runTransient(TransientConfig.oneTick(0.05));
            assertTrue(tran.completed());
            assertEquals(0.0, tran.voltageAtEnd("vout"));
            assertEquals(0.0, tran.currentAtEnd("R1"));

            session.cancelTransient();
            session.onTopologyChanged();
            session.onParameterChanged("R1", new ComponentValue.Resistance(2200.0));
            assertFalse(session.isTransientRunning());
            session.close();
            session.close();
        }
    }

    @Test
    void recognizesRcFilterByShapeNotName() {
        Circuit circuit = Circuit.empty("renamed-fixture");
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(ComponentType.VOLTAGE_SOURCE, "V1",
                new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(ComponentType.RESISTOR, "R1",
                new ComponentValue.Resistance(1000.0), vin, vout);
        circuit.addComponent(ComponentType.CAPACITOR, "C1",
                new ComponentValue.Capacitance(1.0E-6), vout, circuit.ground());

        assertEquals(AnalyticalResults.STUB_RC_FILTER_VOUT_DC,
                new StubEngine().openSession(circuit).runOperatingPoint().nodeVoltages().get("vout"));
    }

    @Test
    void returnsZerosForUnknownCircuit() {
        try (var engine = new StubEngine(); var session = engine.openSession(Circuits.voltageDivider())) {
            assertEquals(0.0, session.runOperatingPoint().nodeVoltages().get("vmid"));
        }
    }
}
