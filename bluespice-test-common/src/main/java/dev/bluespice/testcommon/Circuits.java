package dev.bluespice.testcommon;

import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.INDUCTOR;
import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;

public final class Circuits {
    private Circuits() {}

    public static Circuit rcFilter() {
        Circuit circuit = Circuit.empty("rc-filter");
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), vin, vout);
        circuit.addComponent(CAPACITOR, "C1", new ComponentValue.Capacitance(1.0E-6), vout, circuit.ground());
        return circuit;
    }

    public static Circuit voltageDivider() {
        Circuit circuit = Circuit.empty("voltage-divider");
        Node vin = circuit.addNode("vin");
        Node vmid = circuit.addNode("vmid");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(10.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), vin, vmid);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(1000.0), vmid, circuit.ground());
        return circuit;
    }

    public static Circuit rlcSeries() {
        Circuit circuit = Circuit.empty("rlc-series");
        Node vin = circuit.addNode("vin");
        Node n1 = circuit.addNode("n1");
        Node n2 = circuit.addNode("n2");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(100.0), vin, n1);
        circuit.addComponent(INDUCTOR, "L1", new ComponentValue.Inductance(1.0E-3), n1, n2);
        circuit.addComponent(CAPACITOR, "C1", new ComponentValue.Capacitance(1.0E-6), n2, circuit.ground());
        return circuit;
    }
}
