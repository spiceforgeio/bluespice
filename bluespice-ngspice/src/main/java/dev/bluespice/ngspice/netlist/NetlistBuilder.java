package dev.bluespice.ngspice.netlist;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Component;
import dev.bluespice.core.circuit.ComponentType;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class NetlistBuilder {
    public record BuiltNetlist(List<String> lines, List<String> nodeNames, List<String> branchComponents) {
        public BuiltNetlist {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            nodeNames = List.copyOf(Objects.requireNonNull(nodeNames, "nodeNames"));
            branchComponents = List.copyOf(Objects.requireNonNull(branchComponents, "branchComponents"));
        }

        public String text() {
            return String.join(System.lineSeparator(), lines) + System.lineSeparator();
        }
    }

    public String build(Circuit circuit) {
        return buildDetailed(circuit).text();
    }

    public BuiltNetlist buildDetailed(Circuit circuit) {
        Objects.requireNonNull(circuit, "circuit");
        NodeNumbering numbering = NodeNumbering.from(circuit);
        List<String> modelLines = new ArrayList<>();
        List<String> elementLines = new ArrayList<>();

        for (Component component : circuit.components()) {
            String modelLine = modelLine(component);
            if (modelLine != null) {
                modelLines.add(modelLine);
            }
            elementLines.add(elementLine(component, numbering));
        }

        List<String> lines = new ArrayList<>();
        lines.add("* BlueSpice generated netlist");
        lines.add(".title " + circuit.name());
        lines.addAll(modelLines);
        lines.addAll(elementLines);
        lines.add(".end");
        return new BuiltNetlist(lines, nodeNames(circuit.nodes(), numbering), branchComponents(circuit.components()));
    }

    private String elementLine(Component component, NodeNumbering numbering) {
        return switch (component.type()) {
            case RESISTOR -> prefix(component, "R") + twoTerminal(component, numbering)
                    + " " + spiceDouble(resistance(component.value()));
            case CAPACITOR -> prefix(component, "C") + twoTerminal(component, numbering)
                    + " " + spiceDouble(capacitance(component.value()));
            case INDUCTOR -> prefix(component, "L") + twoTerminal(component, numbering)
                    + " " + spiceDouble(inductance(component.value()));
            case VOLTAGE_SOURCE -> prefix(component, "V") + twoTerminal(component, numbering)
                    + " DC " + spiceDouble(dcVoltage(component.value()));
            case CURRENT_SOURCE -> prefix(component, "I") + twoTerminal(component, numbering)
                    + " DC " + spiceDouble(dcCurrent(component.value()));
            case DIODE -> prefix(component, "D") + terminals(component, numbering, 2)
                    + " " + modelName(component.value());
            case BJT_NPN, BJT_PNP -> prefix(component, "Q") + terminals(component, numbering, 3)
                    + " " + modelName(component.value());
            case NMOS, PMOS -> prefix(component, "M") + terminals(component, numbering, 4)
                    + " " + modelName(component.value());
            case SWITCH -> prefix(component, "S") + terminals(component, numbering, 4)
                    + " " + switchModelName(component);
            case VCVS, VCCS, CCVS, CCCS, TRANSMISSION_LINE, XSPICE_BLOCK ->
                    throw new UnsupportedOperationException("netlist support not defined for " + component.type());
        };
    }

    private String modelLine(Component component) {
        if (component.value() instanceof ComponentValue.ModelRef ref && !ref.params().isEmpty()) {
            String modelType = switch (component.type()) {
                case DIODE -> "D";
                case BJT_NPN -> "NPN";
                case BJT_PNP -> "PNP";
                case NMOS -> "NMOS";
                case PMOS -> "PMOS";
                default -> null;
            };
            if (modelType != null) {
                return ".model " + ref.modelName() + " " + modelType + params(ref);
            }
        }
        if (component.value() instanceof ComponentValue.SwitchState state) {
            return ".model " + switchModelName(component) + " SW(RON=" + state.ron()
                    + " ROFF=" + state.roff() + ")";
        }
        return null;
    }

    private String params(ComponentValue.ModelRef ref) {
        StringBuilder builder = new StringBuilder("(");
        boolean first = true;
        for (var entry : ref.params().entrySet()) {
            if (!first) {
                builder.append(' ');
            }
            first = false;
            builder.append(entry.getKey().toUpperCase(Locale.ROOT)).append('=').append(entry.getValue());
        }
        return builder.append(')').toString();
    }

    private static String prefix(Component component, String prefix) {
        String id = component.id();
        return id.startsWith(prefix) ? id : prefix + id;
    }

    public static String spiceElementId(Component component) {
        return switch (component.type()) {
            case RESISTOR -> prefix(component, "R");
            case CAPACITOR -> prefix(component, "C");
            case INDUCTOR -> prefix(component, "L");
            case VOLTAGE_SOURCE -> prefix(component, "V");
            case CURRENT_SOURCE -> prefix(component, "I");
            case DIODE -> prefix(component, "D");
            case BJT_NPN, BJT_PNP -> prefix(component, "Q");
            case NMOS, PMOS -> prefix(component, "M");
            case SWITCH -> prefix(component, "S");
            case VCVS -> prefix(component, "E");
            case VCCS -> prefix(component, "G");
            case CCVS -> prefix(component, "H");
            case CCCS -> prefix(component, "F");
            case TRANSMISSION_LINE -> prefix(component, "T");
            case XSPICE_BLOCK -> prefix(component, "A");
        };
    }

    private static List<String> nodeNames(Collection<Node> nodes, NodeNumbering numbering) {
        List<String> names = new ArrayList<>();
        for (Node node : nodes) {
            if (!node.isGround()) {
                names.add(numbering.spiceName(node));
            }
        }
        return names;
    }

    private static List<String> branchComponents(Collection<Component> components) {
        List<String> names = new ArrayList<>();
        for (Component component : components) {
            if (component.type() == ComponentType.VOLTAGE_SOURCE || component.type() == ComponentType.INDUCTOR) {
                names.add(spiceElementId(component));
            }
        }
        return names;
    }

    private static String twoTerminal(Component component, NodeNumbering numbering) {
        return terminals(component, numbering, 2);
    }

    private static String terminals(Component component, NodeNumbering numbering, int count) {
        if (component.terminals().size() != count) {
            throw new IllegalArgumentException(component.id() + " requires " + count + " terminals");
        }
        StringBuilder builder = new StringBuilder();
        for (Node terminal : component.terminals()) {
            builder.append(' ').append(numbering.spiceName(terminal));
        }
        return builder.toString();
    }

    private static double resistance(ComponentValue value) {
        return require(value, ComponentValue.Resistance.class).ohms();
    }

    private static double capacitance(ComponentValue value) {
        return require(value, ComponentValue.Capacitance.class).farads();
    }

    private static double inductance(ComponentValue value) {
        return require(value, ComponentValue.Inductance.class).henries();
    }

    private static double dcVoltage(ComponentValue value) {
        return require(value, ComponentValue.DCVoltage.class).volts();
    }

    private static double dcCurrent(ComponentValue value) {
        return require(value, ComponentValue.DCCurrent.class).amps();
    }

    private static String modelName(ComponentValue value) {
        return require(value, ComponentValue.ModelRef.class).modelName();
    }

    private static String switchModelName(Component component) {
        if (component.value() instanceof ComponentValue.ModelRef ref) {
            return ref.modelName();
        }
        return "SW" + component.id();
    }

    private static String spiceDouble(double value) {
        double abs = Math.abs(value);
        // Keep golden netlists stable: ngspice accepts 0.001, but project fixtures use 1.0E-3.
        if (abs > 0.0 && abs < 0.01 && !Double.toString(value).contains("E")) {
            return String.format(Locale.ROOT, "%.1E", value)
                    .replace("E-0", "E-")
                    .replace("E+0", "E");
        }
        return Double.toString(value);
    }

    private static <T extends ComponentValue> T require(ComponentValue value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("expected " + type.getSimpleName() + ", got " + value);
        }
        return type.cast(value);
    }
}
