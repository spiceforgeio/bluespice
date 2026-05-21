package dev.bluespice.ngspice.netlist;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Node;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NodeNumbering {
    private final Map<Long, String> spiceNames;

    private NodeNumbering(Map<Long, String> spiceNames) {
        this.spiceNames = Map.copyOf(spiceNames);
    }

    public static NodeNumbering from(Circuit circuit) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Node node : circuit.nodes()) {
            String spiceName = node.isGround() ? "0" : node.label();
            names.put(node.internalId(), spiceName);
        }
        return new NodeNumbering(names);
    }

    public String spiceName(Node node) {
        String spiceName = spiceNames.get(node.internalId());
        if (spiceName == null) {
            throw new IllegalArgumentException("unknown node: " + node);
        }
        return spiceName;
    }
}
