package dev.bluespice.core.circuit;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.INDUCTOR;
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
        circuit.addComponent(INDUCTOR, "L1", new ComponentValue.Inductance(1.0E-3), a, circuit.ground());
        circuit.addComponent(INDUCTOR, "L2", new ComponentValue.Inductance(4.0E-3), b, circuit.ground());
        circuit.addMutualCoupling("K1", "L1", "L2", 0.99);

        Circuit snapshot = circuit.snapshot();
        assertNotSame(circuit.getNode("a"), snapshot.getNode("a"));
        assertEquals(circuit.getNode("a"), snapshot.getNode("a"));
        assertEquals(circuit.getMutualCoupling("K1"), snapshot.getMutualCoupling("K1"));

        circuit.updateValue("R1", new ComponentValue.Resistance(200.0));
        circuit.updateMutualCoupling("K1", 0.5);
        assertEquals(new ComponentValue.Resistance(100.0), snapshot.getComponent("R1").value());
        assertEquals(new ComponentValue.Resistance(200.0), circuit.getComponent("R1").value());
        assertEquals(0.99, snapshot.getMutualCoupling("K1").couplingCoefficient());
        assertEquals(0.5, circuit.getMutualCoupling("K1").couplingCoefficient());
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

    @Test
    void validatesMutualCouplingsAndRemovesDependents() {
        Circuit circuit = Circuit.empty("coupled");
        Node p1 = circuit.addNode("p1");
        Node p2 = circuit.addNode("p2");
        Node s1 = circuit.addNode("s1");
        Node s2 = circuit.addNode("s2");
        circuit.addComponent(INDUCTOR, "Lp", new ComponentValue.Inductance(1.0), p1, p2);
        circuit.addComponent(INDUCTOR, "Ls", new ComponentValue.Inductance(4.0), s1, s2);
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(1.0), p1, circuit.ground());

        MutualCoupling coupling = circuit.addMutualCoupling("1", "Lp", "Ls", 0.98);

        assertEquals("1", coupling.id());
        assertEquals("Lp", coupling.firstInductorId());
        assertEquals("Ls", coupling.secondInductorId());
        assertEquals(coupling, circuit.getMutualCoupling("1"));
        assertThrows(IllegalArgumentException.class, () -> circuit.addMutualCoupling("2", "Ls", "Lp", 0.5));
        assertThrows(IllegalArgumentException.class, () -> circuit.addMutualCoupling("3", "Lp", "R1", 0.5));
        assertThrows(java.util.NoSuchElementException.class, () -> circuit.addMutualCoupling("4", "Lp", "missing", 0.5));
        assertThrows(IllegalArgumentException.class, () -> circuit.addMutualCoupling("5", "Lp", "Ls", 0.0));
        assertThrows(IllegalArgumentException.class, () -> circuit.addMutualCoupling("6", "Lp", "Ls", 1.1));

        circuit.removeComponent("Ls");

        assertTrue(circuit.mutualCouplings().isEmpty());
    }

    private static final class NodeTestHelper {
        private static Node foreignNode() {
            return Circuit.empty("foreign").addNode("a");
        }
    }
}
