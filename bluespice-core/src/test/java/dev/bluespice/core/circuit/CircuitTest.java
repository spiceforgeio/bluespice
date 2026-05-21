package dev.bluespice.core.circuit;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CircuitTest {
    @Test
    void addRemoveNodesAndComponents() {
        Circuit circuit = Circuit.empty("test");
        Node a = circuit.addNode("a");
        Node b = circuit.addNode("b");

        Component component = circuit.addComponent(
                RESISTOR, "R1", new ComponentValue.Resistance(100.0), a, b);

        assertEquals("test", circuit.name());
        assertEquals(a, circuit.getNode("a"));
        assertEquals(component, circuit.getComponent("R1"));
        assertTrue(component.isLinear());

        circuit.updateValue("R1", new ComponentValue.Resistance(220.0));
        assertEquals(new ComponentValue.Resistance(220.0), circuit.getComponent("R1").value());

        circuit.removeNode(a);
        assertFalse(circuit.components().stream().anyMatch(c -> c.id().equals("R1")));
        assertThrows(IllegalArgumentException.class, () -> circuit.removeNode(circuit.ground()));
    }

    @Test
    void snapshotIsIsolatedFromOriginal() {
        Circuit circuit = Circuit.empty("snapshot");
        Node a = circuit.addNode("a");
        Node b = circuit.addNode("b");
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(100.0), a, b);

        Circuit snapshot = circuit.snapshot();
        assertNotSame(circuit.getNode("a"), snapshot.getNode("a"));
        assertEquals(circuit.getNode("a"), snapshot.getNode("a"));

        circuit.updateValue("R1", new ComponentValue.Resistance(200.0));
        assertEquals(new ComponentValue.Resistance(100.0), snapshot.getComponent("R1").value());
        assertEquals(new ComponentValue.Resistance(200.0), circuit.getComponent("R1").value());
    }

    @Test
    void validatesDuplicateAndMissingObjects() {
        Circuit circuit = Circuit.empty("validation");
        Node a = circuit.addNode("a");

        assertEquals(circuit.ground(), circuit.addNode("gnd"));
        assertThrows(IllegalArgumentException.class, () -> circuit.addNode("a"));
        assertThrows(IllegalArgumentException.class, () ->
                circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), a, NodeTestHelper.foreignNode()));
        assertThrows(IllegalArgumentException.class, () ->
                circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0)));
    }

    private static final class NodeTestHelper {
        private static Node foreignNode() {
            return Circuit.empty("foreign").addNode("a");
        }
    }
}
