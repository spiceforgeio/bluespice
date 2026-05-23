package dev.bluespice.core.circuit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import static dev.bluespice.core.circuit.CircuitValidation.requireText;

/**
 * Mutable circuit graph used as the input model for simulations.
 *
 * <p>The graph methods are synchronized so callers can safely take a {@link #snapshot()} while
 * another thread owns a session. Simulation sessions still require explicit dirty notifications
 * after the caller mutates topology or parameter values.
 */
public final class Circuit {
    private final String name;
    private final Map<String, Node> nodesByLabel;
    private final Map<String, Component> componentsById;
    private long nextNodeId;

    private Circuit(String name) {
        this.name = requireText(name, "name");
        this.nodesByLabel = new LinkedHashMap<>();
        this.componentsById = new LinkedHashMap<>();
        this.nextNodeId = 1;
        nodesByLabel.put("0", new Node("0", 0, true));
    }

    private Circuit(String name, long nextNodeId, Map<String, Node> nodesByLabel,
                    Map<String, Component> componentsById) {
        this.name = name;
        this.nextNodeId = nextNodeId;
        this.nodesByLabel = nodesByLabel;
        this.componentsById = componentsById;
    }

    /**
     * Creates an empty circuit with a ground node named {@code 0}.
     */
    public static Circuit empty(String name) {
        return new Circuit(name);
    }

    /**
     * Adds and returns a non-ground node.
     */
    public synchronized Node addNode(String label) {
        String normalized = normalizeLabel(label);
        if (isGroundLabel(normalized)) {
            return ground();
        }
        if (nodesByLabel.containsKey(normalized)) {
            throw new IllegalArgumentException("node already exists: " + normalized);
        }
        Node node = new Node(normalized, nextNodeId++, false);
        nodesByLabel.put(normalized, node);
        return node;
    }

    /**
     * Returns the canonical ground node.
     */
    public synchronized Node ground() {
        return nodesByLabel.get("0");
    }

    /**
     * Looks up a node by label.
     */
    public synchronized Node getNode(String label) {
        String normalized = normalizeLabel(label);
        Node node = nodesByLabel.get(isGroundLabel(normalized) ? "0" : normalized);
        if (node == null) {
            throw new NoSuchElementException("node not found: " + normalized);
        }
        return node;
    }

    /**
     * Removes a node and any components connected to it.
     */
    public synchronized void removeNode(Node node) {
        Objects.requireNonNull(node, "node");
        if (node.isGround()) {
            throw new IllegalArgumentException("ground node cannot be removed");
        }
        Node stored = nodesByLabel.get(node.label());
        if (node != stored) {
            throw new NoSuchElementException("node not found: " + node.label());
        }
        nodesByLabel.remove(node.label());
        componentsById.values().removeIf(component -> component.terminals().contains(node));
    }

    /**
     * Adds a two-terminal component.
     */
    public Component addComponent(ComponentType type, String id, ComponentValue value, Node positive, Node negative) {
        return addComponent(type, id, value, new Node[] {positive, negative});
    }

    /**
     * Adds a component with explicit terminals.
     */
    public synchronized Component addComponent(ComponentType type, String id, ComponentValue value, Node... terminals) {
        String normalizedId = requireText(id, "id");
        if (componentsById.containsKey(normalizedId)) {
            throw new IllegalArgumentException("component already exists: " + normalizedId);
        }
        List<Node> validated = validateTerminals(terminals);
        Component component = new Component(normalizedId, type, value, validated);
        componentsById.put(normalizedId, component);
        return component;
    }

    /**
     * Removes a component by id.
     */
    public synchronized void removeComponent(String id) {
        String normalizedId = requireText(id, "id");
        if (componentsById.remove(normalizedId) == null) {
            throw new NoSuchElementException("component not found: " + normalizedId);
        }
    }

    /**
     * Replaces a component value without changing topology.
     */
    public synchronized void updateValue(String id, ComponentValue newValue) {
        String normalizedId = requireText(id, "id");
        Component component = getComponent(normalizedId);
        componentsById.put(normalizedId, component.withValue(newValue));
    }

    /**
     * Returns a stable copy of the current node set.
     */
    public synchronized Set<Node> nodes() {
        return new LinkedHashSet<>(nodesByLabel.values());
    }

    /**
     * Returns a stable copy of the current component collection.
     */
    public synchronized Collection<Component> components() {
        return List.copyOf(componentsById.values());
    }

    /**
     * Looks up a component by id.
     */
    public synchronized Component getComponent(String id) {
        String normalizedId = requireText(id, "id");
        Component component = componentsById.get(normalizedId);
        if (component == null) {
            throw new NoSuchElementException("component not found: " + normalizedId);
        }
        return component;
    }

    /**
     * Circuit name used in generated netlists and diagnostics.
     */
    public String name() {
        return name;
    }

    /**
     * Creates a deep copy safe for background-thread simulation.
     *
     * <p>The copy contains independent node and component objects with the same labels, ids, and
     * values. Later mutations to the original circuit do not affect the snapshot.
     */
    public synchronized Circuit snapshot() {
        Map<String, Node> copiedNodes = new LinkedHashMap<>();
        Map<Long, Node> byInternalId = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : nodesByLabel.entrySet()) {
            Node node = entry.getValue();
            Node copy = new Node(node.label(), node.internalId(), node.isGround());
            copiedNodes.put(entry.getKey(), copy);
            byInternalId.put(copy.internalId(), copy);
        }

        Map<String, Component> copiedComponents = new LinkedHashMap<>();
        for (Component component : componentsById.values()) {
            List<Node> terminals = component.terminals().stream()
                    .map(node -> byInternalId.get(node.internalId()))
                    .toList();
            copiedComponents.put(component.id(), new Component(
                    component.id(), component.type(), component.value(), terminals));
        }
        return new Circuit(name, nextNodeId, copiedNodes, copiedComponents);
    }

    private List<Node> validateTerminals(Node[] terminals) {
        Objects.requireNonNull(terminals, "terminals");
        List<Node> validated = new ArrayList<>(terminals.length);
        for (Node terminal : terminals) {
            Objects.requireNonNull(terminal, "terminal");
            Node stored = terminal.isGround() ? nodesByLabel.get("0") : nodesByLabel.get(terminal.label());
            if (terminal != stored) {
                throw new IllegalArgumentException("terminal does not belong to this circuit: " + terminal);
            }
            validated.add(stored);
        }
        return validated;
    }

    private static String normalizeLabel(String label) {
        return requireText(label, "label");
    }

    private static boolean isGroundLabel(String label) {
        return "0".equals(label) || "gnd".equalsIgnoreCase(label);
    }

}
