package dev.bluespice.core.circuit;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TopologyAnalyzerTest {
    @Test
    void singleConnectedCircuit_returnsOneComponent() {
        Circuit circuit = Circuit.empty("topology");
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(5.0), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), vin, vout);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(1.0), vout, circuit.ground());

        var components = Topology.connectedComponents(circuit);

        assertEquals(1, components.size());
        assertEquals(Set.of(circuit.ground(), vin, vout), components.getFirst());
        assertFalse(Topology.isDisconnected(circuit));
    }

    @Test
    void twoDisjointCircuits_returnsTwoComponents() {
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
        assertTrue(Topology.isDisconnected(circuit));
    }

    @Test
    void split_producesCorrectSubCircuits() {
        Circuit circuit = Circuit.empty("split");
        Node a1 = circuit.addNode("a1");
        Node b1 = circuit.addNode("b1");
        Node a2 = circuit.addNode("a2");
        Node b2 = circuit.addNode("b2");
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), a1, b1);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(2.0), a2, b2);

        List<Circuit> parts = Topology.split(circuit);

        assertEquals(2, parts.size());
        assertComponentIds(parts.get(0), "R1");
        assertComponentIds(parts.get(1), "R2");
    }

    @Test
    void gndAlwaysIncludedInExactlyOneComponent() {
        Circuit circuit = Circuit.empty("ground-shared");
        Node a = circuit.addNode("a");
        Node b = circuit.addNode("b");
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), a, circuit.ground());
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(1.0), b, circuit.ground());

        var components = Topology.connectedComponents(circuit);

        assertEquals(2, components.size());
        assertEquals(1, components.stream().filter(nodes -> nodes.contains(circuit.ground())).count());
    }

    @Test
    void split_singleComponent_returnsSingleSubCircuit() {
        Circuit circuit = Circuit.empty("single");
        Node a = circuit.addNode("a");
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), a, circuit.ground());

        List<Circuit> parts = Topology.split(circuit);

        assertEquals(1, parts.size());
        assertComponentIds(parts.getFirst(), "R1");
        assertEquals(Set.of("0", "a"), nodeLabels(parts.getFirst()));
    }

    @Test
    void split_gndOnlyCircuit_returnsSingleGroundCircuit() {
        Circuit circuit = Circuit.empty("ground-only");

        List<Circuit> parts = Topology.split(circuit);

        assertEquals(1, parts.size());
        assertTrue(parts.getFirst().components().isEmpty());
        assertEquals(Set.of("0"), nodeLabels(parts.getFirst()));
    }

    @Test
    void split_threeComponentsSharingOnlyGround_returnsThreeSubCircuits() {
        Circuit circuit = Circuit.empty("three");
        Node a = circuit.addNode("a");
        Node b = circuit.addNode("b");
        Node c = circuit.addNode("c");
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), a, circuit.ground());
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(2.0), b, circuit.ground());
        circuit.addComponent(RESISTOR, "R3", new ComponentValue.Resistance(3.0), c, circuit.ground());

        List<Circuit> parts = Topology.split(circuit);

        assertEquals(3, parts.size());
        assertComponentIds(parts.get(0), "R1");
        assertComponentIds(parts.get(1), "R2");
        assertComponentIds(parts.get(2), "R3");
        assertTrue(parts.stream().allMatch(part -> nodeLabels(part).contains("0")));
    }

    private static void assertComponentIds(Circuit circuit, String... expected) {
        assertEquals(Set.of(expected), circuit.components().stream()
                .map(Component::id)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static Set<String> nodeLabels(Circuit circuit) {
        return circuit.nodes().stream()
                .map(Node::label)
                .collect(java.util.stream.Collectors.toSet());
    }
}
