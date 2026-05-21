package dev.bluespice.core.circuit;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TopologyAnalyzerTest {
    @Test
    void findsConnectedComponentsWithBfs() {
        Circuit circuit = Circuit.empty("topology");
        Node a = circuit.addNode("a");
        Node b = circuit.addNode("b");
        Node c = circuit.addNode("c");
        Node d = circuit.addNode("d");
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), a, b);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(1.0), c, d);

        var components = Topology.connectedComponents(circuit);

        assertEquals(3, components.size());
        assertTrue(components.contains(Set.of(circuit.ground())));
        assertTrue(components.contains(Set.of(a, b)));
        assertTrue(components.contains(Set.of(c, d)));
    }
}
