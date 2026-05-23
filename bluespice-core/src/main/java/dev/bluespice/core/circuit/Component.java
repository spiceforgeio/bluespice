package dev.bluespice.core.circuit;

import java.util.List;
import java.util.Objects;

import static dev.bluespice.core.circuit.CircuitValidation.requireText;

/**
 * Immutable component instance inside a {@link Circuit}.
 */
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

    /**
     * Stable component id supplied by the caller.
     */
    public String id() {
        return id;
    }

    /**
     * Electrical component category.
     */
    public ComponentType type() {
        return type;
    }

    /**
     * Current component value or model reference.
     */
    public ComponentValue value() {
        return value;
    }

    /**
     * Ordered terminals connected to circuit nodes.
     */
    public List<Node> terminals() {
        return terminals;
    }

    /**
     * Returns whether the component is linear for partitioning and fast-path decisions.
     */
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
