package dev.bluespice.testcommon;

import static dev.bluespice.core.circuit.ComponentType.BJT_NPN;
import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.CURRENT_SOURCE;
import static dev.bluespice.core.circuit.ComponentType.DIODE;
import static dev.bluespice.core.circuit.ComponentType.INDUCTOR;
import static dev.bluespice.core.circuit.ComponentType.NMOS;
import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Circuits {
    private Circuits() {}

    public static Circuit rcFilter() {
        return rcSmall();
    }

    public static Circuit rcSmall() {
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
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(500.0), vin, vmid);
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

    public static Circuit diodeClamp() {
        Circuit circuit = Circuit.empty("diode-clamp");
        Node vin = circuit.addNode("vin");
        Node vclamped = circuit.addNode("vclamped");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), vin, vclamped);
        circuit.addComponent(DIODE, "D1", new ComponentValue.ModelRef(
                "D1N4148",
                orderedParams("IS", 2.52E-9, "N", 1.752, "RS", 0.568)),
                vclamped,
                circuit.ground());
        return circuit;
    }

    public static Circuit bjtAmp() {
        Circuit circuit = Circuit.empty("bjt-amp");
        Node vcc = circuit.addNode("vcc");
        Node vout = circuit.addNode("vout");
        Node base = circuit.addNode("base");
        Node emitter = circuit.addNode("emitter");
        circuit.addComponent(VOLTAGE_SOURCE, "VCC", new ComponentValue.DCVoltage(12.0), vcc, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "VBIAS", new ComponentValue.DCVoltage(1.2), base, circuit.ground());
        circuit.addComponent(RESISTOR, "RC", new ComponentValue.Resistance(4700.0), vcc, vout);
        circuit.addComponent(RESISTOR, "RE", new ComponentValue.Resistance(1000.0), emitter, circuit.ground());
        circuit.addComponent(BJT_NPN, "Q1", new ComponentValue.ModelRef(
                "Q2N2222",
                orderedParams("IS", 1.0E-14, "BF", 100.0, "VAF", 100.0)),
                vout,
                base,
                emitter);
        return circuit;
    }

    public static Circuit mosfetSwitch() {
        Circuit circuit = Circuit.empty("mosfet-switch");
        Node vdd = circuit.addNode("vdd");
        Node gate = circuit.addNode("gate");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "VDD", new ComponentValue.DCVoltage(5.0), vdd, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "VGATE", new ComponentValue.DCVoltage(5.0), gate, circuit.ground());
        circuit.addComponent(RESISTOR, "RLOAD", new ComponentValue.Resistance(1000.0), vdd, vout);
        circuit.addComponent(NMOS, "M1", new ComponentValue.ModelRef(
                "NMOS_GENERIC",
                orderedParams("VTO", 2.0, "KP", 0.001, "LAMBDA", 0.02)),
                vout,
                gate,
                circuit.ground(),
                circuit.ground());
        return circuit;
    }

    public static Circuit currentDivider() {
        Circuit circuit = Circuit.empty("current-divider");
        Node isplit = circuit.addNode("isplit");
        circuit.addComponent(CURRENT_SOURCE, "I1", new ComponentValue.DCCurrent(0.001), circuit.ground(), isplit);
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1000.0), isplit, circuit.ground());
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(3000.0), isplit, circuit.ground());
        return circuit;
    }

    private static Map<String, Double> orderedParams(Object... values) {
        Map<String, Double> params = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            params.put((String) values[i], (Double) values[i + 1]);
        }
        return params;
    }
}
