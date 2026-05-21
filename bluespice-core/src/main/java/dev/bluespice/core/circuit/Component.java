package dev.bluespice.core.circuit;

import java.util.List;
import java.util.Objects;

import static dev.bluespice.core.circuit.CircuitValidation.requireText;

public final class Component {
    private final String id;
    private final ComponentType type;
    private final ComponentValue value;
    private final List<Node> terminals;

    Component(String id, ComponentType type, ComponentValue value, List<Node> terminals) {
        this.id = requireText(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
        this.terminals = List.copyOf(terminals);
        if (this.terminals.isEmpty()) {
            throw new IllegalArgumentException("components must have at least one terminal");
        }
    }

    public String id() {
        return id;
    }

    public ComponentType type() {
        return type;
    }

    public ComponentValue value() {
        return value;
    }

    public List<Node> terminals() {
        return terminals;
    }

    public boolean isLinear() {
        return switch (type) {
            case RESISTOR, CAPACITOR, INDUCTOR, VOLTAGE_SOURCE, CURRENT_SOURCE,
                    VCVS, VCCS, CCVS, CCCS, TRANSMISSION_LINE -> true;
            case DIODE, BJT_NPN, BJT_PNP, NMOS, PMOS, SWITCH, XSPICE_BLOCK -> false;
        };
    }

    Component withValue(ComponentValue newValue) {
        return new Component(id, type, newValue, terminals);
    }
}
